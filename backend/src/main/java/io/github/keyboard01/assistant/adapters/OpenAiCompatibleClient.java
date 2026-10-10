package io.github.keyboard01.assistant.adapters;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/** 生成模型调用（OpenAI 兼容协议）。 */
@Component
public class OpenAiCompatibleClient {

    public TestResult probeChat(Map<String, String> cfg) {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty()) {
            return new TestResult(false, "未填写生成模型接口地址", null);
        }
        if (apiKey.isEmpty()) {
            return new TestResult(false, "未填写生成模型 API Key", null);
        }
        if (model.isEmpty()) {
            return new TestResult(false, "未填写生成模型名", null);
        }
        try {
            JsonNode response = HttpSupport.postJson(baseUrl + "/chat/completions", apiKey, Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "只回复两个字：正常")),
                    "max_tokens", 64,
                    "temperature", 0));
            JsonNode message = response.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                // 推理型模型（如 推理型模型）会把内容放在 reasoning_content，且小 max_tokens 下 content 可能为空
                content = message.path("reasoning_content").asText("");
            }
            if (content.isBlank()) {
                String finish = response.path("choices").path(0).path("finish_reason").asText("unknown");
                return new TestResult(false,
                        "接口已响应但没有正文（finish_reason=" + finish + "），请核对模型名是否可用、max_tokens 是否过小",
                        HttpSupport.brief(response));
            }
            return new TestResult(true, "生成模型可用：模型 " + model + "，返回「" + content.trim() + "」", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "调用失败：" + ex.getMessage(), null);
        }
    }
}
