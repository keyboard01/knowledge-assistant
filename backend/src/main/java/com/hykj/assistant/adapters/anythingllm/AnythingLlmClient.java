package com.hykj.assistant.adapters.anythingllm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.settings.SettingModels.TestResult;

/**
 * AnythingLLM 是唯一被允许出现的第三方知识内核。
 * 本项目只通过这里访问它，便于后续整体替换。
 *
 * <p>注意这个类**没有 public**：包外再也看不见它，业务层能依赖的只有 {@link KernelGateway}（管理面，A1）
 * 与 {@code retrieval/KnowledgeRetriever}（检索面）。"只有 adapters/anythingllm 碰内核"于是从一条
 * 需要自觉遵守的约定，变成了编译期事实——包外随手 import 一下这个类，代码直接过不了编译。</p>
 *
 * <p>（包可见的 @Component 仍然是 Spring 的合法候选 Bean：它不是非静态内部类，反射实例化会打开访问权限。
 * 前提是别给它加需要 CGLIB 代理的东西——一旦代理，包可见类会失败。）</p>
 *
 * <p>内核私有格式（{@link RawHit} 与 {@code vectorSearch}）不往包外露：那是检索适配器的内部细节，
 * 暴露出去就等于把"内核原生分数"当成了公共契约。</p>
 */
@Component
class AnythingLlmClient implements KernelGateway {

    public boolean hasApiKey(Map<String, String> cfg) {
        return !HttpSupport.text(cfg.get("apiKey")).isEmpty();
    }

    /** 只检查服务是否在线，不做鉴权。 */
    public boolean online(Map<String, String> cfg) throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        JsonNode ping = HttpSupport.get(baseUrl + "/api/ping", null);
        return ping.path("online").asBoolean(false);
    }

    public List<Workspace> listWorkspaces(Map<String, String> cfg) throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        JsonNode response = HttpSupport.get(baseUrl + "/api/v1/workspaces", apiKey);
        JsonNode workspaces = response.path("workspaces");
        List<Workspace> list = new ArrayList<>();
        if (workspaces.isArray()) {
            for (JsonNode item : workspaces) {
                list.add(new Workspace(item.path("name").asText(""), item.path("slug").asText("")));
            }
        } else if (workspaces.isObject()) {
            workspaces.fields().forEachRemaining(entry ->
                    list.add(new Workspace(entry.getValue().path("name").asText(""), entry.getKey())));
        }
        return list;
    }

    /**
     * 内核的 slug 由 name 推导：slugify(name)，推不出来就退化成随机 uuid。
     * 所以创建时传英文 slug 才能拿到稳定可寻址的工作区；中文显示名另外用 updateWorkspace 改。
     * chatMode 固定 query：本项目只把内核当检索用，不给它聊天的机会。
     */
    public Workspace createWorkspace(Map<String, String> cfg, String name) throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        JsonNode response = HttpSupport.postJson(baseUrl + "/api/v1/workspace/new", apiKey,
                Map.of("name", name, "chatMode", "query"));
        JsonNode workspace = response.path("workspace");
        return new Workspace(workspace.path("name").asText(name), workspace.path("slug").asText(""));
    }

    /** 只改显示名，内核的可写字段白名单里没有 slug，改名不会动摇寻址。 */
    public void updateWorkspace(Map<String, String> cfg, String slug, Map<String, Object> updates)
            throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        HttpSupport.postJson(baseUrl + "/api/v1/workspace/" + slug + "/update", apiKey, updates);
    }

    /**
     * 上传即入库：内核在同一个请求里完成解析、切分与向量化，响应返回时就能检索到。
     * 注意 filename 会成为内核里的文档名，必须用 ASCII——检索命中要靠它反查本地文档。
     */
    public UploadedDocument uploadDocument(Map<String, String> cfg, String workspaceSlug, String fileName,
                                           String contentType, byte[] content, Map<String, String> metadata)
            throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("addToWorkspaces", workspaceSlug);
        if (metadata != null && !metadata.isEmpty()) {
            fields.put("metadata", HttpSupport.MAPPER.writeValueAsString(metadata));
        }
        JsonNode response = HttpSupport.postMultipart(baseUrl + "/api/v1/document/upload", apiKey,
                fields, "file", fileName, contentType, content);
        if (!response.path("success").asBoolean(false)) {
            throw new IOException("内核拒绝上传：" + response.path("error").asText("未给出原因"));
        }
        JsonNode document = response.path("documents").path(0);
        String location = document.path("location").asText("");
        if (location.isEmpty()) {
            throw new IOException("内核响应里没有文档位置，无法登记：" + HttpSupport.brief(response));
        }
        return new UploadedDocument(location, document.path("title").asText(fileName));
    }

    /** 从内核彻底删除文档（连同各工作区里的向量）。 */
    public void removeDocuments(Map<String, String> cfg, List<String> locations)
            throws IOException, InterruptedException {
        if (locations == null || locations.isEmpty()) {
            return;
        }
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        HttpSupport.deleteJson(baseUrl + "/api/v1/system/remove-documents", apiKey, Map.of("names", locations));
    }

    /**
     * 内核原生向量检索：问题由内核用它自己配置的 embedding 现场向量化，再在指定工作区里找最相似的片段。
     * 它同时验证了两条不变量——"内核与后端 embedding 一致"和"检索链路通"。检索结果里的 title 是
     * 内核文档名（形如 QM-ZD-2026-003_V3.2.pdf），这是把命中片段反查回本地 document 行的唯一钥匙。
     * 这里只做传输：正文清洗与字段映射在 AnythingLlmRetriever 里做。
     */
    public List<RawHit> vectorSearch(Map<String, String> cfg, String workspaceSlug, String query,
                                     int topN, double scoreThreshold)
            throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        JsonNode response = HttpSupport.postJson(
                baseUrl + "/api/v1/workspace/" + workspaceSlug + "/vector-search", apiKey,
                Map.of("query", query, "topN", topN, "scoreThreshold", scoreThreshold));
        List<RawHit> hits = new ArrayList<>();
        JsonNode results = response.path("results");
        if (results.isArray()) {
            for (JsonNode item : results) {
                hits.add(new RawHit(
                        item.path("id").asText(""),
                        item.path("text").asText(""),
                        stringMap(item.path("metadata")),
                        item.path("distance").asDouble(0d),
                        item.path("score").asDouble(0d)));
            }
        }
        return hits;
    }

    /** 一条原始命中：内核给什么就是什么。 */
    public record RawHit(String id, String text, Map<String, String> metadata, double distance, double score) {
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                values.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText(""));
            });
        }
        return values;
    }

    public KernelSnapshot systemSnapshot(Map<String, String> cfg) throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        JsonNode settings = HttpSupport.get(baseUrl + "/api/v1/system", apiKey).path("settings");
        return new KernelSnapshot(
                string(settings, "EmbeddingEngine"),
                string(settings, "EmbeddingBasePath"),
                string(settings, "EmbeddingModelPref"),
                string(settings, "EmbeddingOutputDimensions"),
                flag(settings, "GenericOpenAiEmbeddingApiKey"),
                flag(settings, "HasExistingEmbeddings"),
                string(settings, "LLMProvider"),
                string(settings, "LLMModel"));
    }

    public Integer vectorCount(Map<String, String> cfg) throws IOException, InterruptedException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        JsonNode node = HttpSupport.get(baseUrl + "/api/v1/system/vector-count", apiKey).path("vectorCount");
        return node.isNumber() ? node.asInt() : null;
    }

    /** 未配置的项内核会返回 null，而 NullNode.asText("") 得到的是字符串 "null"。 */
    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull() && value.asBoolean(false);
    }

    public TestResult probe(Map<String, String> cfg) {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        if (baseUrl.isEmpty()) {
            return new TestResult(false, "未填写 AnythingLLM 服务地址", null);
        }
        try {
            JsonNode ping = HttpSupport.get(baseUrl + "/api/ping", null);
            boolean online = ping.path("online").asBoolean(false);
            if (!online) {
                return new TestResult(false, "服务可达但未就绪（/api/ping 返回 online != true）", ping.toString());
            }
            if (apiKey.isEmpty()) {
                return new TestResult(true, "服务在线；未填写 Developer API Key，只能访问公开接口", null);
            }
            try {
                JsonNode response = HttpSupport.get(baseUrl + "/api/v1/workspaces", apiKey);
                JsonNode workspaces = response.path("workspaces");
                int count = workspaces.isArray() || workspaces.isObject() ? workspaces.size() : -1;
                return new TestResult(true,
                        count >= 0 ? "连接成功，API Key 有效，可见工作区 " + count + " 个"
                                   : "连接成功，API Key 有效",
                        null);
            } catch (IOException authFailure) {
                return new TestResult(false,
                        "服务在线（/api/ping 正常），但工作区接口调用失败，API Key 可能无效：" + authFailure.getMessage(),
                        null);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "连接被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "连接失败：" + ex.getMessage(), null);
        }
    }
}
