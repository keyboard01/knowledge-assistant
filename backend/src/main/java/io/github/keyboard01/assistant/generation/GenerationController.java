package io.github.keyboard01.assistant.generation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.keyboard01.assistant.retrieval.Evidence;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.CurrentUser;

/**
 * 问答接口。身份取自服务端会话，知识域与问题由前端传——可见范围不在请求里，
 * 它只能由 {@link CurrentUser} 推出，这是 T06 定下的规矩。
 *
 * <p>两个入口共用同一段生成与判定逻辑：{@code /answer} 一次给全部，{@code /answer/stream} 边生成边推。
 * 流式只是把"等得久"这件事变好看，判定标准一个字都没放松——{@code verdict} 事件才是校验结论，
 * 在那之前前端拿到的正文都算"未校验"。</p>
 */
@RestController
@RequestMapping("/api/generation")
public class GenerationController {

    private static final Logger log = LoggerFactory.getLogger(GenerationController.class);

    /** 流式连接的最长存活时间：够一次长回答，又能自动回收忘记关闭的浏览器连接。 */
    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final AtomicInteger STREAM_THREADS = new AtomicInteger();

    /**
     * SSE 必须把活干在别的线程上：SseEmitter 要求请求线程尽快返回，占着 Tomcat 工作线程
     * 等模型吐字，并发一上来就把线程池拖干。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "generation-stream-" + STREAM_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public record AnswerRequest(String domainCode, String question, Integer topN) {
    }

    private final GenerationService service;

    public GenerationController(GenerationService service) {
        this.service = service;
    }

    @PostMapping("/answer")
    public GeneratedAnswer answer(@RequestBody AnswerRequest request) throws IOException, InterruptedException {
        return service.answer(CurrentUser.subject(), request.domainCode(), request.question(), request.topN());
    }

    /**
     * 流式问答：{@code evidences} →（{@code delta}…）→ {@code verdict}，出错则是 {@code error}。
     *
     * <p>注意 SSE 的固有代价：一旦推了第一个事件，HTTP 状态码就已经是 200，之后任何失败都只能
     * 用 {@code error} 事件表达——所以前端不能只看状态码，必须处理 {@code error}。</p>
     */
    @PostMapping(value = "/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(@RequestBody AnswerRequest request) {
        // 身份必须在请求线程上取出来：CurrentUser 是 ThreadLocal，换了线程就没了
        AccessSubject subject = CurrentUser.subject();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            try {
                GeneratedAnswer answer = service.answerStreaming(subject, request.domainCode(), request.question(),
                        request.topN(), new GenerationService.StreamListener() {
                            @Override
                            public void onEvidences(List<Evidence> evidences, boolean insufficient) {
                                send(emitter, "evidences",
                                        Map.of("evidences", evidences, "insufficient", insufficient));
                            }

                            @Override
                            public void onDelta(String text) {
                                send(emitter, "delta", Map.of("text", text));
                            }
                        });
                send(emitter, "verdict", answer);
            } catch (IOException ex) {
                log.warn("流式生成失败：模型出口不可用", ex);
                send(emitter, "error", Map.of("error", "生成模型调用失败：" + ex.getMessage()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                send(emitter, "error", Map.of("error", "生成被中断"));
            } catch (RuntimeException ex) {
                log.warn("流式生成失败", ex);
                send(emitter, "error", Map.of("error", ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 客户端中途关页面会让推送抛异常，这是常态而不是故障，记一笔就够。 */
    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE 推送失败（客户端可能已断开）：{}", ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    /**
     * 模型调不通必须与"知识库答不上来"分开报：前者是配置或服务的问题，重试或找运维；
     * 后者是知识库里确实没有。混成同一个 200，用户会把系统故障当成"我们没这个制度"。
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onModelUnavailable(IOException ex) {
        log.warn("生成失败：模型出口不可用", ex);
        return Map.of("error", "生成模型调用失败，本次问答未完成：" + ex.getMessage());
    }
}
