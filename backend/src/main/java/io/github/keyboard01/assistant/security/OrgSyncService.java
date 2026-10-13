package io.github.keyboard01.assistant.security;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.dingtalk.DingTalkApi;

/**
 * 钉钉组织同步：递归部门 → 拉人 → 按 (external_source, external_id) upsert 本地账号。
 *
 * <p>**有界**：部门数与递归深度都设上限，避免大组织把一次 HTTP 请求拖死；超出即截断并如实标记。</p>
 * <p>**绝不建管理员**（{@link OrgSyncPlan#roleOrDefault} 兜底），也不覆盖人工建的本地账号名。</p>
 */
@Service
public class OrgSyncService {

    private static final Logger log = LoggerFactory.getLogger(OrgSyncService.class);

    private static final int MAX_DEPARTMENTS = 500;
    private static final int MAX_USERS = 20_000;

    public record SyncResult(int departments, int users, int created, int updated, int skipped, boolean truncated) {
    }

    private final DingTalkApi api;
    private final UserRepository users;

    public OrgSyncService(DingTalkApi api, UserRepository users) {
        this.api = api;
        this.users = users;
    }

    public SyncResult sync(DingTalkConfig cfg) {
        if (!cfg.usable()) {
            throw new IllegalStateException("钉钉未启用或凭据不完整，无法同步");
        }

        List<OrgSyncPlan.DingUser> collected = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visitedDepts = new HashSet<>();
        queue.add(cfg.rootDeptId());
        boolean truncated = false;

        try {
            while (!queue.isEmpty()) {
                if (visitedDepts.size() >= MAX_DEPARTMENTS || collected.size() >= MAX_USERS) {
                    truncated = true;
                    break;
                }
                String deptId = queue.poll();
                if (!visitedDepts.add(deptId)) {
                    continue;
                }
                for (DingTalkApi.OrgUser user : api.users(cfg, deptId)) {
                    collected.add(new OrgSyncPlan.DingUser(user.userId(), user.name(), user.deptId(),
                            user.active()));
                }
                for (DingTalkApi.Dept dept : api.subDepartments(cfg, deptId)) {
                    if (!visitedDepts.contains(dept.deptId())) {
                        queue.add(dept.deptId());
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("组织同步被中断");
        } catch (IOException ex) {
            throw new IllegalStateException("组织同步失败：" + ex.getMessage(), ex);
        }

        Set<String> reserved = new HashSet<>(users.usernamesWithoutExternal());
        List<OrgSyncPlan.Upsert> plan = OrgSyncPlan.plan(collected, reserved, cfg.defaultRole());

        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (OrgSyncPlan.Upsert upsert : plan) {
            Optional<Long> existing = users.findIdByExternal(OrgSyncPlan.SOURCE, upsert.externalId());
            if (existing.isPresent()) {
                users.updateExternal(existing.get(), upsert.displayName(), upsert.roleCode(), upsert.deptCode(),
                        upsert.enabled());
                updated++;
            } else {
                try {
                    users.insertExternal(upsert.username(), upsert.displayName(), upsert.roleCode(),
                            upsert.deptCode(), OrgSyncPlan.SOURCE, upsert.externalId(), upsert.enabled());
                    created++;
                } catch (DuplicateKeyException ex) {
                    skipped++;
                    log.warn("同步建号撞名，已跳过：username={}", upsert.username());
                }
            }
        }
        log.info("钉钉组织同步完成：部门 {}，用户 {}，新增 {}，更新 {}，跳过 {}，截断={}",
                visitedDepts.size(), collected.size(), created, updated, skipped, truncated);
        return new SyncResult(visitedDepts.size(), collected.size(), created, updated, skipped, truncated);
    }

    /** SSO 单人 upsert：登录时把一个钉钉用户落到本地，返回可签发会话的账号。 */
    public AppUser upsertUser(DingTalkConfig cfg, DingTalkApi.OrgUser ding) {
        if (ding == null || ding.userId() == null || ding.userId().isBlank()) {
            throw new IllegalStateException("钉钉未返回有效用户标识");
        }
        Optional<Long> existing = users.findIdByExternal(OrgSyncPlan.SOURCE, ding.userId());
        if (existing.isPresent()) {
            AppUser current = users.findById(existing.get())
                    .orElseThrow(() -> new IllegalStateException("同步后读取账号失败"));
            // 保留已有角色（管理员可能已手工调整），只更新显示名/部门/启用
            users.updateExternal(current.id(), display(ding, current.username()), current.roleCode(),
                    ding.deptId(), ding.active());
            return users.findById(current.id()).orElse(current);
        }
        String username = OrgSyncPlan.username(ding.userId());
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalStateException("登录名 " + username + " 已被本地账号占用，无法为钉钉用户建号");
        }
        long id = users.insertExternal(username, display(ding, username),
                OrgSyncPlan.roleOrDefault(cfg.defaultRole()), ding.deptId(), OrgSyncPlan.SOURCE, ding.userId(),
                ding.active());
        return users.findById(id).orElseThrow(() -> new IllegalStateException("建号后读取失败"));
    }

    private static String display(DingTalkApi.OrgUser ding, String fallback) {
        return ding.name() == null || ding.name().isBlank() ? fallback : ding.name().trim();
    }
}
