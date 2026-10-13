package io.github.keyboard01.assistant.adapters.dingtalk;

import java.io.IOException;
import java.util.List;

import io.github.keyboard01.assistant.security.DingTalkConfig;

/**
 * 钉钉开放平台的取数接口。抽成接口是为了让组织同步/SSO 能用手写 fake 单测（本仓库不起 Spring 上下文）。
 */
public interface DingTalkApi {

    record Dept(String deptId, String name, String parentId) {
    }

    record OrgUser(String userId, String name, String deptId, boolean active) {
    }

    List<Dept> subDepartments(DingTalkConfig cfg, String deptId) throws IOException, InterruptedException;

    List<OrgUser> users(DingTalkConfig cfg, String deptId) throws IOException, InterruptedException;

    OrgUser getUser(DingTalkConfig cfg, String userId) throws IOException, InterruptedException;

    String userIdByAuthCode(DingTalkConfig cfg, String authCode) throws IOException, InterruptedException;
}
