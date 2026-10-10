package io.github.keyboard01.assistant.adapters;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/** 向量化模型的连通性探针（OpenAI 兼容协议），供「系统配置」页的“测试连接”使用。 */
@Component
public class EmbeddingProbeClient {

    public TestResult probeEmbedding(Map<String, String> cfg) {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty()) {
            return new TestResult(false, "未填写向量化接口地址", null);
        }
        if (apiKey.isEmpty()) {
            return new TestResult(false, "未填写向量化 API Key", null);
        }
        if (model.isEmpty()) {
            return new TestResult(false, "未填写向量化模型名", null);
        }
        try {
            JsonNode response = HttpSupport.postJson(baseUrl + "/embeddings", apiKey,
                    Map.of("model", model, "input", List.of("连接测试")));
            JsonNode embedding = response.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                return new TestResult(false, "接口已响应，但未返回向量", response.toString());
            }
            return new TestResult(true,
                    "向量化可用：模型 " + model + "，维度 " + embedding.size(), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("404")) {
                return new TestResult(false,
                        "接口不存在（404）：该服务可能不提供 embedding 能力。"
                                + "请确认所用服务是否提供 OpenAI 兼容的 /embeddings 端点。"
                                + "原始信息：" + message,
                        null);
            }
            return new TestResult(false, "调用失败：" + message, null);
        }
    }
}
