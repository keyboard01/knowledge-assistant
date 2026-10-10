package com.hykj.assistant.adapters.rerank;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.settings.SettingModels.TestResult;

/**
 * 重排模型调用（HTTP {@code /rerank}）。一个适配器同时兼容两种常见返回形状：
 * <ul>
 *   <li>OpenAI / TEI 形状：顶层 {@code results:[{index, relevance_score}]}（本地 重排模型 走 TEI/vLLM 就是它）；</li>
 *   <li>OpenAI 兼容服务 重排接口：{@code output.results:[{index, relevance_score}]}。</li>
 * </ul>
 *
 * <p>超时是**独立且短的**（{@link #TIMEOUT} 3s）：一次深度分析会触发约 10 次检索、每次一个重排请求，
 * 复用 {@code HttpSupport} 默认的 25s 读超时会让一个挂掉的重排服务吃掉深度通道 120s 的预算。
 * 重排是"锦上添花"，超过 3s 就当没有它——由上层 fail-open 退回 RRF 原序。</p>
 */
@Component
public class RerankClient {

    /** 重排等不起：超过这个时间就放弃（上层退回原序）。 */
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** 单次最多送多少条候选去重排：够覆盖 topN 的重排窗口，又不至于把请求体撑大。 */
    static final int MAX_DOCUMENTS = 32;

    /**
     * 打重排分。返回顺序与 {@code documents} 一一对应。
     *
     * @throws IOException 网络失败 / HTTP 4xx、5xx / 超时 / 配置不完整
     */
    public List<Double> score(Map<String, String> cfg, String query, List<String> documents)
            throws IOException, InterruptedException {
        Endpoint endpoint = endpoint(cfg);
        List<String> docs = documents.size() > MAX_DOCUMENTS ? documents.subList(0, MAX_DOCUMENTS) : documents;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("query", query);
        body.put("documents", docs);
        body.put("top_n", docs.size());
        JsonNode response = HttpSupport.postJson(endpoint.baseUrl() + "/rerank", endpoint.apiKey(), body, TIMEOUT);
        return parse(response, docs.size());
    }

    /** 设置页「测试连接」用：发一条最小请求，能拿到分数即视为可用。 */
    public TestResult probe(Map<String, String> cfg) {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty()) {
            return new TestResult(false, "未填写重排接口地址", null);
        }
        if (apiKey.isEmpty()) {
            return new TestResult(false, "未填写重排 API Key", null);
        }
        if (model.isEmpty()) {
            return new TestResult(false, "未填写重排模型名", null);
        }
        try {
            List<Double> scores = score(cfg, "连接测试", List.of("这是一段用于连通性测试的文本。"));
            if (scores.isEmpty()) {
                return new TestResult(false, "接口已响应，但未返回重排分数", null);
            }
            return new TestResult(true, "重排可用：模型 " + model + "，示例分 " + scores.get(0), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            return new TestResult(false, "调用失败：" + message, null);
        }
    }

    /**
     * 把响应里的重排结果按 {@code index} 归位成与入参等长的分数数组。
     *
     * <p>不信 provider 的返回顺序——它的 {@code results} 通常是按分数降序、不是按入参序，
     * 直接顺序读会把分数张冠李戴。缺 {@code index} 的条目按出现次序归位；只有越界/重复/非数值才算坏响应。
     * 某些服务会截断到 {@code top_n} 只回前几条，缺失的候选按"最低分"处理（排最后），而不是整体作废。</p>
     */
    static List<Double> parse(JsonNode response, int expected) {
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            results = response.path("output").path("results");
        }
        if (!results.isArray()) {
            throw new IllegalStateException("重排返回里没有 results 数组：" + HttpSupport.brief(response));
        }
        Double[] ordered = new Double[expected];
        int seen = 0;
        for (JsonNode item : results) {
            int index = item.has("index") ? item.path("index").asInt(-1) : seen;
            JsonNode scoreNode = item.has("relevance_score") ? item.path("relevance_score") : item.path("score");
            if (index < 0 || index >= expected || !scoreNode.isNumber()) {
                throw new IllegalStateException("重排返回的条目无法解析：" + HttpSupport.brief(item));
            }
            if (ordered[index] != null) {
                throw new IllegalStateException("重排返回出现重复的 index：" + index);
            }
            ordered[index] = scoreNode.asDouble();
            seen++;
        }
        List<Double> scores = new ArrayList<>(expected);
        for (Double value : ordered) {
            scores.add(value == null ? Double.NEGATIVE_INFINITY : value);
        }
        return scores;
    }

    private static Endpoint endpoint(Map<String, String> cfg) throws IOException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new IOException("重排配置不完整：请在系统配置里填写重排的接口地址与模型名");
        }
        return new Endpoint(baseUrl, HttpSupport.text(cfg.get("apiKey")), model);
    }

    private record Endpoint(String baseUrl, String apiKey, String model) {
    }
}
