package io.github.keyboard01.assistant.observe;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.observe.ObservationModels.FeedbackSummary;
import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.security.RequireRole;

/**
 * 用户反馈（P3）。
 *
 * <p><b>这个控制器刻意不是 admin-only</b>——与 {@link ObserveController} 的整体口径相反，
 * 理由很具体：评价必须是**提问人自己**提交的才有统计价值，管理员代劳等于自己给自己打分。
 * 所以权限不靠角色，靠"这条账本是不是你提的问"（见 {@link FeedbackService#submit}），
 * 管理员只是额外允许代评。</p>
 *
 * <p>读接口（汇总）仍是 admin：那是全局统计，和账本本身一样敏感。</p>
 */
@RestController
@RequestMapping("/api/observe")
public class FeedbackController {

    /** 提交体。{@code rating} 只认 ±1，{@code reason} 只认白名单，见 {@link FeedbackService#validate}。 */
    public record FeedbackRequest(Integer rating, String reason, String comment) {
    }

    public record FeedbackResponse(long traceId, int rating, String reason, boolean commented) {
    }

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping("/traces/{id}/feedback")
    public FeedbackResponse feedback(@PathVariable long id, @RequestBody FeedbackRequest request) {
        FeedbackService.Validated saved = service.submit(id, request.rating(), request.reason(),
                request.comment(), CurrentUser.require());
        return new FeedbackResponse(id, saved.rating(), saved.reason(), saved.comment() != null);
    }

    /** 参数类错误给 400：与 IngestController 同一条约定（本项目没有全局 advice，各控制器自己声明）。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    /** 可选原因清单：前端 chips 从这里取，避免前后端各写一份枚举。 */
    @GetMapping("/feedback/reasons")
    public Map<String, Object> reasons() {
        return Map.of("reasons", FeedbackService.REASONS,
                "maxComment", FeedbackService.MAX_COMMENT);
    }

    @GetMapping("/feedback/summary")
    @RequireRole("admin")
    public FeedbackSummary summary() {
        return service.summary();
    }
}
