package io.github.keyboard01.assistant.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钉钉登录入口。整段 {@code /api/auth/dingtalk/**} 免登录（在 WebConfig 里排除），
 * 因为调用它的正是"还没登录、想登录"的人。
 */
@RestController
@RequestMapping("/api/auth/dingtalk")
public class DingTalkController {

    public record CallbackRequest(String authCode) {
    }

    private final DingTalkAuthService service;

    public DingTalkController(DingTalkAuthService service) {
        this.service = service;
    }

    /** 前端据此决定要不要显示"钉钉登录"。 */
    @GetMapping("/enabled")
    public Map<String, Object> enabled() {
        return Map.of("enabled", service.config().usable());
    }

    /** 钉钉 JSAPI（dd.config）需要的公开参数；**不含** appSecret。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        DingTalkConfig cfg = service.config();
        return Map.of("enabled", cfg.usable(), "corpId", cfg.corpId(), "agentId", cfg.agentId());
    }

    /** 免登回调：前端在钉钉客户端内拿到 authCode 后回传，换取本系统会话。 */
    @PostMapping("/callback")
    public AuthService.LoginResult callback(@RequestBody CallbackRequest request) {
        return service.loginByAuthCode(request.authCode());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
