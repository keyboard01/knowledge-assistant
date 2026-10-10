package io.github.keyboard01.assistant.deep;

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

import io.github.keyboard01.assistant.deep.DeepModels.DeepAnswer;
import io.github.keyboard01.assistant.deep.DeepModels.Section;
import io.github.keyboard01.assistant.deep.DeepModels.Step;
import io.github.keyboard01.assistant.deep.DeepModels.SubQuestion;
import io.github.keyboard01.assistant.retrieval.Evidence;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.CurrentUser;

/**
 * 深度分析接口（P3a）。
 *
 * <p>三个入口：{@code /answer}（一次拿完）、{@code /answer/stream}（边探索边推，能看着它一轮轮查）、
 * {@code /cancel}（取消）。身份一律取自服务端会话——请求体里没有用户，也没有可见范围。</p>
 *
 * <p>流式的价值在这一条链路上比快速问答更大：深度分析要花几十秒，用户需要看到"它在做什么"，
 * 否则只会以为卡死了。所以推的不只是正文，还有 {@code plan}（拆成了哪几条子问题）与 {@code step}
 * （每一步查了什么、新增了几条证据）。</p>
 */
@RestController
@RequestMapping("/api/deep")
public class DeepController {

    private static final Logger log = LoggerFactory.getLogger(DeepController.class);

    /** 深度分析本来就慢（多轮检索 + 分步生成），连接存活时间给得比快速问答长。 */
    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final AtomicInteger STREAM_THREADS = new AtomicInteger();

    /**
     * 与快速问答分开的线程池：一次深度分析能占住几十秒，共用池会让长任务把短问答的线程挤干。
     * 池子也刻意小（4）：深度分析是重活，宁可排队，也不要让并发把模型出口打满。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "deep-stream-" + STREAM_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 请求体。
     *
     * <p>{@code runId} 由前端生成（可空）：前端先拿到 id，才能在"还在拆解"的时候就能取消。
     * 预算字段只能往小里填，超上限按上限算——见 {@link DeepBudget#of}。</p>
     */
    public record DeepRequest(String domainCode, String question, String runId, Integer maxSubQuestions,
                              Integer maxToolCalls, Integer maxRounds, Long maxElapsedMs,
                              Integer maxCompletionTokens) {

        DeepBudget budget() {
            return DeepBudget.of(maxSubQuestions, maxToolCalls, maxRounds, maxElapsedMs, maxCompletionTokens);
        }
    }

    public record CancelRequest(String runId) {
    }

    private final DeepAnalysisService service;
    private final DeepRunRegistry registry;

    public DeepController(DeepAnalysisService service, DeepRunRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @PostMapping("/answer")
    public DeepAnswer answer(@RequestBody DeepRequest request) throws IOException, InterruptedException {
        return service.answer(CurrentUser.subject(), request.domainCode(), request.question(), request.budget(),
                request.runId(), DeepAnalysisService.DeepListener.NONE);
    }

    /**
     * 流式深度分析：{@code plan} → {@code step}… → {@code evidences} →（{@code delta}… / {@code section}…）
     * → {@code verdict}，出错则是 {@code error}。
     *
     * <p>同一个坑与快速问答一样：一旦推了第一个事件，HTTP 状态码就固定是 200，之后任何失败都只能用
     * {@code error} 事件表达——所以前端不能只看状态码。</p>
     */
    @PostMapping(value = "/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(@RequestBody DeepRequest request) {
        // 身份必须在请求线程上取出来：CurrentUser 是 ThreadLocal，换了线程就没了
        AccessSubject subject = CurrentUser.subject();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            try {
                DeepAnswer answer = service.answer(subject, request.domainCode(), request.question(),
                        request.budget(), request.runId(), new DeepAnalysisService.DeepListener() {
                            @Override
                            public boolean streaming() {
                                return true;
                            }

                            @Override
                            public void onPlan(String runId, List<SubQuestion> plan, DeepBudget budget) {
                                send(emitter, "plan", Map.of("runId", runId, "plan", plan,
                                        "budget", budget.describe()));
                            }

                            @Override
                            public void onStep(Step step) {
                                send(emitter, "step", Map.of("step", step));
                            }

                            @Override
                            public void onEvidences(List<Evidence> evidences) {
                                send(emitter, "evidences", Map.of("evidences", evidences,
                                        "insufficient", evidences.isEmpty()));
                            }

                            @Override
                            public void onDelta(String text) {
                                send(emitter, "delta", Map.of("text", text));
                            }

                            @Override
                            public void onSection(Section section) {
                                send(emitter, "section", Map.of("section", section));
                            }
                        });
                send(emitter, "verdict", answer);
            } catch (IOException ex) {
                log.warn("深度分析失败：模型出口不可用", ex);
                send(emitter, "error", Map.of("error", "生成模型调用失败：" + ex.getMessage()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                send(emitter, "error", Map.of("error", "深度分析被中断"));
            } catch (RuntimeException ex) {
                log.warn("深度分析失败", ex);
                send(emitter, "error", Map.of("error", ex.getMessage() == null
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 取消。
     *
     * <p>取消是"请在下个步骤边界停下"，所以返回 200 而不是 202/204：用户要的是一句确定的答复
     * （到底有没有找到这次运行），而不是一个状态码。</p>
     */
    @PostMapping("/cancel")
    public DeepRunRegistry.Cancel cancel(@RequestBody CancelRequest request) {
        return registry.cancel(request == null ? null : request.runId(), CurrentUser.subject().username());
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

    /** 模型调不通必须与"知识库答不上来"分开报：重试或找运维，与"换问法"是两件事。 */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onModelUnavailable(IOException ex) {
        log.warn("深度分析失败：模型出口不可用", ex);
        return Map.of("error", "生成模型调用失败，本次深度分析未完成：" + ex.getMessage());
    }
}
