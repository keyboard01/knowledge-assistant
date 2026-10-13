package io.github.keyboard01.assistant.adapters.vision;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.keyboard01.assistant.adapters.HttpSupport;
import io.github.keyboard01.assistant.media.ImageThumbs;
import io.github.keyboard01.assistant.media.VisionCaptioner;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/**
 * 视觉模型（OpenAI 兼容 `/chat/completions`，content 数组带 `image_url`）。
 *
 * <p>文本版的 {@code OpenAiCompatibleLanguageModel} 只能发字符串，发不了图片，所以单开一个适配器。
 * 图片先**降采样**（最长边 ≤1024 的 JPEG）再 base64：base64 会膨胀约 1/3，原样发大图既慢又贵。</p>
 */
@Component
public class OpenAiCompatibleVisionClient implements VisionCaptioner {

    private static final String PROMPT = """
            你是图片理解助手。请完成两件事：
            1) 用一句中文描述这张图片画的是什么、适合用在什么场合；
            2) 如果图中有文字、表格或公式，请原样转录。
            只返回 JSON，不要任何解释或代码块标记：{"caption":"...","ocr":"..."}""";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_SIDE = 1024;

    /** 1×1 透明 PNG：探针用它验证"该模型确实接收图片输入"。 */
    private static final String TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    /**
     * 无状态：配置由调用方传入（与其它适配器同一约定）——否则它会与
     * {@code SettingService} 形成构造期循环依赖（SettingService 要注入探针，探针又要读配置）。
     */
    public OpenAiCompatibleVisionClient() {
    }

    @Override
    public boolean enabled(Map<String, String> cfg) {
        return usable(cfg);
    }

    @Override
    public Caption caption(byte[] imageBytes, String mimeType, Map<String, String> cfg)
            throws IOException, InterruptedException {
        if (!usable(cfg)) {
            throw new IOException("视觉模型未启用或未配置");
        }
        byte[] scaled = ImageThumbs.downscale(imageBytes, MAX_SIDE, "jpg");
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(scaled);
        JsonNode response = HttpSupport.postJson(HttpSupport.normalizeBase(cfg.get("baseUrl")) + "/chat/completions",
                HttpSupport.text(cfg.get("apiKey")), body(HttpSupport.text(cfg.get("model")), dataUrl), TIMEOUT);
        return parse(contentOf(response));
    }

    /** 系统配置页的「测试连接」：发一张 1×1 PNG，能返回内容即视为可用。 */
    public TestResult probe(Map<String, String> cfg) {
        if (!usable(cfg)) {
            return new TestResult(false, "视觉模型未启用或配置不完整（需要 baseUrl / API Key / 模型名）", null);
        }
        try {
            String dataUrl = "data:image/png;base64," + TEST_PNG_BASE64;
            JsonNode response = HttpSupport.postJson(
                    HttpSupport.normalizeBase(cfg.get("baseUrl")) + "/chat/completions",
                    HttpSupport.text(cfg.get("apiKey")),
                    body(HttpSupport.text(cfg.get("model")), dataUrl), Duration.ofSeconds(30));
            return new TestResult(true, "视觉模型可用（已接受图片输入）", HttpSupport.brief(response));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "调用失败：" + ex.getMessage(), null);
        }
    }

    private static boolean usable(Map<String, String> cfg) {
        return Boolean.parseBoolean(HttpSupport.text(cfg.get("enabled")))
                && !HttpSupport.text(cfg.get("baseUrl")).isEmpty()
                && !HttpSupport.text(cfg.get("apiKey")).isEmpty()
                && !HttpSupport.text(cfg.get("model")).isEmpty();
    }

    private static Map<String, Object> body(String model, String dataUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", PROMPT),
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
        body.put("temperature", 0.1);
        body.put("max_tokens", 800);
        return body;
    }

    private static String contentOf(JsonNode response) {
        return HttpSupport.text(response.path("choices").path(0).path("message").path("content").asText(""));
    }

    private static Caption parse(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            text = newline >= 0 ? text.substring(newline + 1) : text;
            int fence = text.lastIndexOf("```");
            text = fence >= 0 ? text.substring(0, fence) : text;
            text = text.trim();
        }
        try {
            JsonNode node = HttpSupport.MAPPER.readTree(text);
            if (node != null && node.isObject()) {
                String caption = HttpSupport.text(node.path("caption").asText(""));
                String ocr = HttpSupport.text(node.path("ocr").asText(""));
                if (!caption.isEmpty() || !ocr.isEmpty()) {
                    return new Caption(caption, ocr);
                }
            }
        } catch (Exception ignore) {
            // 模型没按 JSON 返回：整段当描述，不因为格式不合规就丢一张图
        }
        return new Caption(text, "");
    }
}
