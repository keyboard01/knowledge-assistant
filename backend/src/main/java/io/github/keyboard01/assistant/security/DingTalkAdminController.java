package io.github.keyboard01.assistant.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 组织同步的触发入口（admin）。 */
@RestController
@RequestMapping("/api/admin/dingtalk")
@RequireRole("admin")
public class DingTalkAdminController {

    private final DingTalkAuthService dingTalkAuthService;
    private final OrgSyncService orgSyncService;

    public DingTalkAdminController(DingTalkAuthService dingTalkAuthService, OrgSyncService orgSyncService) {
        this.dingTalkAuthService = dingTalkAuthService;
        this.orgSyncService = orgSyncService;
    }

    @PostMapping("/sync")
    public OrgSyncService.SyncResult sync() {
        return orgSyncService.sync(dingTalkAuthService.config());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
