package io.github.keyboard01.assistant.retrieval;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.security.CurrentUser;

/**
 * 检索接口。身份来自服务端会话（{@link CurrentUser}），前端不能传用户或可见范围——
 * 过滤条件只由服务端按登录身份生成。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalController.class);

    public record SearchRequest(String domainCode, String question, Integer topN, Double minScore) {
    }

    private final RetrievalService service;

    public RetrievalController(RetrievalService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public RetrievalResult search(@RequestBody SearchRequest request) throws IOException, InterruptedException {
        return service.search(CurrentUser.subject(), request.domainCode(), request.question(),
                request.topN(), request.minScore());
    }

    /** 检索已经能区分"没有证据"和"被权限挡住"，所以参数类错误只报 400，不伪装成空结果。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    /**
     * 内核不可用绝不能伪装成"库里没有"：一个是系统坏了、该找运维，一个是知识库确实没这个内容、
     * 该换问法。返回 503 而不是 500，让前端能说"稍后重试"而不是"服务器错误"。
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onKernelUnavailable(IOException ex) {
        log.warn("检索失败：知识库内核不可用", ex);
        return Map.of("error", "知识库内核暂时不可用，本次检索未执行，请稍后重试");
    }
}
