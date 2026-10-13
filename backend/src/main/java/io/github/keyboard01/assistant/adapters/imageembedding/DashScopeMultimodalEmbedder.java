package io.github.keyboard01.assistant.adapters.imageembedding;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.keyboard01.assistant.adapters.HttpSupport;
import io.github.keyboard01.assistant.media.ImageEmbedder;
import io.github.keyboard01.assistant.media.ImageThumbs;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/**
 * 阿里云百炼「多模态 embedding」客户端（默认模型 `qwen2.5-vl-embedding`）。
 *
 * <p>接口形状：`POST {baseUrl}`（默认 `…/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding`），
 * 头 `Authorization: Bearer <API Key>`，体 `{"model":"…","input":{"contents":[{"text":…} | {"image":数据URL}]}}`，
 * 向量在 `output.embeddings[0].embedding`。**默认维度 1024**，正好与库里的 `vector(1024)` 对齐，
 * 因此不传 dim（少一处会写错的参数）；维度不符时由上层校验报错。</p>
 *
 * <p>图片先降采样再 base64，省带宽与费用。配置留空即禁用，不做任何网络调用。</p>
 */
@Component
public class DashScopeMultimodalEmbedder implements ImageEmbedder {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
    private static final int MAX_SIDE = 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * 无状态：配置由调用方传入（与其它适配器同一约定）——否则它会与
     * {@code SettingService} 形成构造期循环依赖（SettingService 要注入探针，探针又要读配置）。
     */
    public DashScopeMultimodalEmbedder() {
    }

    @Override
    public boolean enabled(Map<String, String> cfg) {
        return usable(cfg);
    }

    @Override
    public String model(Map<String, String> cfg) {
        return text(cfg.get("model"));
    }

    @Override
    public float[] embedImage(byte[] imageBytes, String mimeType, Map<String, String> cfg)
            throws IOException, InterruptedException {
        byte[] scaled = ImageThumbs.downscale(imageBytes, MAX_SIDE, "jpg");
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(scaled);
        return embed(Map.of("image", dataUrl), cfg);
    }

    @Override
    public float[] embedText(String text, Map<String, String> cfg) throws IOException, InterruptedException {
        return embed(Map.of("text", text == null ? "" : text), cfg);
    }

    private float[] embed(Map<String, String> content, Map<String, String> cfg) throws IOException, InterruptedException {
        if (!usable(cfg)) {
            throw new IOException("多模态向量模型未启用或未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", text(cfg.get("model")));
        body.put("input", Map.of("contents", List.of(content)));
        JsonNode response = HttpSupport.postJson(endpoint(cfg), text(cfg.get("apiKey")), body, TIMEOUT);
        return parseVector(response);
    }

    /** 探测：编码一小段文本，能拿到向量即视为可用（同时暴露真实维度）。 */
    public TestResult probe(Map<String, String> cfg) {
        if (!usable(cfg)) {
            return new TestResult(false, "未启用或配置不完整（需要 API Key 与模型名）", null);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", text(cfg.get("model")));
            body.put("input", Map.of("contents", List.of(Map.of("text", "连接测试"))));
            JsonNode response = HttpSupport.postJson(endpoint(cfg), text(cfg.get("apiKey")), body,
                    Duration.ofSeconds(30));
            return new TestResult(true, "多模态向量可用：维度 " + parseVector(response).length, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "调用失败：" + ex.getMessage(), null);
        }
    }

    /** 防御式解析：优先百炼的 `output.embeddings[0].embedding`，再退兼容形状。 */
    static float[] parseVector(JsonNode response) throws IOException {
        JsonNode node = response.path("output").path("embeddings").path(0).path("embedding");
        if (!isVector(node)) {
            node = response.path("output").path("embedding");
        }
        if (!isVector(node)) {
            node = response.path("data").path(0).path("embedding");
        }
        if (!isVector(node)) {
            throw new IOException("多模态向量接口未返回向量：" + HttpSupport.brief(response));
        }
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private static boolean isVector(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty();
    }

    private static boolean usable(Map<String, String> cfg) {
        return Boolean.parseBoolean(text(cfg.get("enabled")))
                && !text(cfg.get("apiKey")).isEmpty()
                && !text(cfg.get("model")).isEmpty();
    }

    private static String endpoint(Map<String, String> cfg) {
        String base = text(cfg.get("baseUrl"));
        return base.isEmpty() ? DEFAULT_ENDPOINT : base;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
