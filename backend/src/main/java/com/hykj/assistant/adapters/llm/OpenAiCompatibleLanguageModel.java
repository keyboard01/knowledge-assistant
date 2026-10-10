package com.hykj.assistant.adapters.llm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.generation.LanguageModel;
import com.hykj.assistant.settings.SettingService;

/**
 * {@link LanguageModel} 的 OpenAI 兼容实现：推理服务、该服务兼容模式、本地 vLLM / Ollama 都走这一条。
 * 地址、Key、模型名全部来自「生成模型出口」配置，业务侧看不到它们。
 *
 * <p>这是适配层，所以只做"翻译"：把提示词翻成请求体、把响应翻成 {@link Completion}，
 * 不做重试、不做截断兜底、不猜模型意图——那些是编排层的事。</p>
 */
@Component
public class OpenAiCompatibleLanguageModel implements LanguageModel {

    /**
     * 单次回答的长度上限。知识问答一两段话足够，但这个值不能太小：
     * 被截断会被上层判成拒答，属于"宁可不说"的代价，所以留足余量。
     */
    private static final int MAX_TOKENS = 1600;

    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final SettingService settingService;

    public OpenAiCompatibleLanguageModel(SettingService settingService) {
        this.settingService = settingService;
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        Endpoint endpoint = endpoint();
        Map<String, Object> body = body(endpoint, systemPrompt, userPrompt);
        body.put("stream", false);

        JsonNode response = HttpSupport.postJson(endpoint.baseUrl() + "/chat/completions",
                endpoint.apiKey(), body);
        JsonNode choice = response.path("choices").path(0);
        JsonNode message = choice.path("message");
        // 推理型模型（推理型模型 一类）正文可能只落在 reasoning_content，这个坑 T02 自检时踩过一次
        String content = firstNonBlank(message.path("content").asText(""),
                message.path("reasoning_content").asText(""));
        JsonNode usage = response.path("usage");
        return new Completion(content, endpoint.model(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                "length".equalsIgnoreCase(choice.path("finish_reason").asText("")));
    }

    @Override
    public Completion streamComplete(String systemPrompt, String userPrompt, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        Endpoint endpoint = endpoint();
        Map<String, Object> body = body(endpoint, systemPrompt, userPrompt);
        body.put("stream", true);
        // 用量只在流末尾的最后一个 chunk 里给出，而且不是所有兼容服务都支持。
        // 拿不到就记 0：账本里一个 0 比一个编出来的估算值诚实。
        body.put("stream_options", Map.of("include_usage", true));

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int[] tokens = new int[2];
        String[] finish = new String[1];
        HttpSupport.postJsonStream(endpoint.baseUrl() + "/chat/completions", endpoint.apiKey(), body, event -> {
            JsonNode usage = event.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                tokens[0] = usage.path("prompt_tokens").asInt(tokens[0]);
                tokens[1] = usage.path("completion_tokens").asInt(tokens[1]);
            }
            JsonNode choice = event.path("choices").path(0);
            if (choice.isMissingNode()) {
                return; // 只带用量的收尾 chunk 没有 choices
            }
            String reason = choice.path("finish_reason").asText("");
            if (!reason.isEmpty()) {
                finish[0] = reason;
            }
            String delta = choice.path("delta").path("content").asText("");
            if (!delta.isEmpty()) {
                answer.append(delta);
                onDelta.accept(delta);
            }
            // 推理型模型的思考过程只做兜底，不往界面上流：把思考当答案显示会误导用户
            String reasoning = choice.path("delta").path("reasoning_content").asText("");
            if (!reasoning.isEmpty()) {
                thinking.append(reasoning);
            }
        });
        return new Completion(answer.isEmpty() ? thinking.toString() : answer.toString(), endpoint.model(),
                tokens[0], tokens[1], "length".equalsIgnoreCase(finish[0]));
    }

    /** 三个必填项的校验收在一处：同步与流式走同一条规则，不会出现"一个入口能跑另一个入口报错"。 */
    private Endpoint endpoint() throws IOException {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_LLM);
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty()) {
            throw new IOException("未配置生成模型接口地址（系统配置 → 生成模型出口）");
        }
        if (apiKey.isEmpty()) {
            throw new IOException("未配置生成模型 API Key（系统配置 → 生成模型出口）");
        }
        if (model.isEmpty()) {
            throw new IOException("未配置生成模型名（系统配置 → 生成模型出口）");
        }
        return new Endpoint(baseUrl, apiKey, model, temperature(cfg.get("temperature")));
    }

    private static Map<String, Object> body(Endpoint endpoint, String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", endpoint.temperature());
        body.put("max_tokens", MAX_TOKENS);
        return body;
    }

    private record Endpoint(String baseUrl, String apiKey, String model, double temperature) {
    }

    private static double temperature(String raw) {
        try {
            return raw == null || raw.isBlank() ? DEFAULT_TEMPERATURE : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_TEMPERATURE;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
