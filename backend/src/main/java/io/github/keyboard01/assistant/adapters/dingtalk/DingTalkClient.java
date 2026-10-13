package io.github.keyboard01.assistant.adapters.dingtalk;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.keyboard01.assistant.adapters.HttpSupport;
import io.github.keyboard01.assistant.security.DingTalkConfig;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/**
 * 钉钉开放平台直连客户端（未引官方 SDK——离线仓库拉不到）。
 *
 * <p>两个必须记住的坑：① access_token 走 **URL query**（{@code HttpSupport} 只支持 Bearer 头）；
 * ② topapi 出错时 **HTTP 仍是 200**，必须逐个响应检查 {@code errcode}。</p>
 *
 * <p>凭据留空时本类不做任何网络调用：{@link #probe} 直接返回"未配置"。</p>
 */
@Component
public class DingTalkClient implements DingTalkApi {

    private static final String BASE = "https://oapi.dingtalk.com";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    /** 钉钉报这几个码就是 token 失效，需作废重取一次。 */
    private static final int ERR_TOKEN_INVALID = 40014;
    private static final int ERR_TOKEN_MISSING = 40001;
    private static final int ERR_TOKEN_EXPIRED = 33001;

    private final AccessTokenCache tokenCache = new AccessTokenCache();

    /** 系统配置页的“测试连接”探针：不抛异常，只报告。 */
    public TestResult probe(Map<String, String> rawCfg) {
        DingTalkConfig cfg = DingTalkConfig.from(rawCfg);
        if (!cfg.usable()) {
            return new TestResult(false, "钉钉未启用或凭据不完整（需要打开开关并填写 appKey 与 appSecret）", null);
        }
        try {
            String token = accessToken(cfg);
            return new TestResult(true, "钉钉凭据可用", "access_token 长度 " + token.length());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "钉钉探测被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "钉钉连接失败：" + ex.getMessage(), null);
        }
    }

    @Override
    public List<Dept> subDepartments(DingTalkConfig cfg, String deptId) throws IOException, InterruptedException {
        JsonNode response = call(cfg, "/topapi/v2/department/listsub", Map.of("dept_id", deptId));
        List<Dept> out = new ArrayList<>();
        for (JsonNode node : response.path("result")) {
            out.add(new Dept(node.path("dept_id").asText(), node.path("name").asText(""),
                    node.path("parent_id").asText("")));
        }
        return out;
    }

    @Override
    public List<OrgUser> users(DingTalkConfig cfg, String deptId) throws IOException, InterruptedException {
        List<OrgUser> out = new ArrayList<>();
        long cursor = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = call(cfg, "/topapi/v2/user/list",
                    Map.of("dept_id", deptId, "cursor", cursor, "size", PAGE_SIZE));
            JsonNode result = response.path("result");
            for (JsonNode node : result.path("list")) {
                out.add(new OrgUser(node.path("userid").asText(), node.path("name").asText(""), deptId,
                        node.path("active").asBoolean(true)));
            }
            if (!result.path("has_more").asBoolean(false)) {
                break;
            }
            cursor = result.path("next_cursor").asLong(0);
        }
        return out;
    }

    @Override
    public OrgUser getUser(DingTalkConfig cfg, String userId) throws IOException, InterruptedException {
        JsonNode response = call(cfg, "/topapi/v2/user/get", Map.of("userid", userId));
        JsonNode node = response.path("result");
        String dept = node.path("dept_id_list").path(0).asText(node.path("dept_id").asText(""));
        return new OrgUser(node.path("userid").asText(userId), node.path("name").asText(""), dept,
                node.path("active").asBoolean(true));
    }

    @Override
    public String userIdByAuthCode(DingTalkConfig cfg, String authCode) throws IOException, InterruptedException {
        JsonNode response = call(cfg, "/topapi/v2/user/getuserinfo", Map.of("code", authCode));
        return response.path("result").path("userid").asText("");
    }

    private JsonNode call(DingTalkConfig cfg, String path, Map<String, Object> body)
            throws IOException, InterruptedException {
        String token = accessToken(cfg);
        try {
            return post(path, token, body);
        } catch (TokenExpiredException ex) {
            // token 失效：作废重取一次再试
            tokenCache.invalidate();
            return post(path, accessToken(cfg), body);
        }
    }

    private JsonNode post(String path, String token, Map<String, Object> body)
            throws IOException, InterruptedException {
        String url = BASE + path + "?access_token=" + enc(token);
        JsonNode response = HttpSupport.postJson(url, null, body);
        check(response);
        return response;
    }

    private String accessToken(DingTalkConfig cfg) throws IOException, InterruptedException {
        String cached = tokenCache.get(cfg.appKey());
        if (cached != null) {
            return cached;
        }
        String url = BASE + "/gettoken?appkey=" + enc(cfg.appKey()) + "&appsecret=" + enc(cfg.appSecret());
        JsonNode response = HttpSupport.get(url, null);
        check(response);
        String token = response.path("access_token").asText("");
        if (token.isEmpty()) {
            throw new IOException("钉钉未返回 access_token");
        }
        tokenCache.put(cfg.appKey(), token, response.path("expires_in").asLong(7200));
        return token;
    }

    private static void check(JsonNode response) throws IOException {
        int code = response.path("errcode").asInt(0);
        if (code == 0) {
            return;
        }
        if (code == ERR_TOKEN_INVALID || code == ERR_TOKEN_MISSING || code == ERR_TOKEN_EXPIRED) {
            throw new TokenExpiredException(code);
        }
        throw new IOException("钉钉接口错误 errcode=" + code + "：" + response.path("errmsg").asText(""));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static final class TokenExpiredException extends IOException {
        TokenExpiredException(int code) {
            super("钉钉 access_token 失效 errcode=" + code);
        }
    }
}
