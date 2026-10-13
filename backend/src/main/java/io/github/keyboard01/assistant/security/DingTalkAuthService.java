package io.github.keyboard01.assistant.security;

import java.io.IOException;

import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.dingtalk.DingTalkApi;
import io.github.keyboard01.assistant.settings.SettingService;

/**
 * 钉钉内免登：{@code authCode → userid → upsert 本地账号 → 签发会话}。
 * 配置留空即禁用；未启用时所有入口都回"未配置"，不做任何网络调用。
 */
@Service
public class DingTalkAuthService {

    private final DingTalkApi api;
    private final SettingService settingService;
    private final OrgSyncService orgSync;
    private final AuthService authService;

    public DingTalkAuthService(DingTalkApi api, SettingService settingService, OrgSyncService orgSync,
                               AuthService authService) {
        this.api = api;
        this.settingService = settingService;
        this.orgSync = orgSync;
        this.authService = authService;
    }

    public DingTalkConfig config() {
        return DingTalkConfig.from(settingService.effective(SettingService.GROUP_DINGTALK));
    }

    public AuthService.LoginResult loginByAuthCode(String authCode) {
        DingTalkConfig cfg = config();
        if (!cfg.usable()) {
            throw new IllegalStateException("钉钉登录未启用或未配置");
        }
        if (authCode == null || authCode.isBlank()) {
            throw new IllegalArgumentException("缺少 authCode");
        }
        try {
            String userId = api.userIdByAuthCode(cfg, authCode);
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException("钉钉未返回 userid，authCode 可能已失效");
            }
            DingTalkApi.OrgUser ding = api.getUser(cfg, userId);
            AppUser user = orgSync.upsertUser(cfg, ding);
            if (!user.enabled()) {
                throw new IllegalStateException("账号已停用，请联系管理员");
            }
            return authService.issueSession(user);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("钉钉登录被中断");
        } catch (IOException ex) {
            throw new IllegalStateException("钉钉登录失败：" + ex.getMessage(), ex);
        }
    }
}
