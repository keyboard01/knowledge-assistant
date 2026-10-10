package io.github.keyboard01.assistant.adapters.layout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.keyboard01.assistant.adapters.HttpSupport;
import io.github.keyboard01.assistant.parse.ParsedBlock;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/**
 * 版面分析服务的 HTTP 出口（P1 解析升级）。
 *
 * <p>契约刻意做得最小，好让 Docling / MinerU / PP-StructureV3 用一层薄适配就能对上：
 * {@code POST {baseUrl}/parse}（multipart 字段名为 {@code file}）→ 返回
 * {@code {blocks:[{kind, text, page, level}]}}。{@code kind} 取
 * HEADING / PARAGRAPH / BULLET / TABLE_ROW（不认识的一律当 PARAGRAPH）。</p>
 *
 * <p>它**不依赖 {@code SettingService}**（与 {@code RerankClient} 同构）：这样设置页的探活能直接用它，
 * 不会和 {@code SettingService} 形成循环依赖。</p>
 */
@Component
public class LayoutClient {

    /** 防御性上限：服务抽风返回十万块会把内存和预览都拖垮。 */
    static final int MAX_BLOCKS = 5000;

    /**
     * 解析一个文件，返回块列表。拿不到 {@code blocks} 数组抛 {@link IOException}（由上层 fail-open）。
     */
    public List<ParsedBlock> parse(Map<String, String> cfg, Path file) throws IOException, InterruptedException {
        String baseUrl = requireBase(cfg);
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        byte[] content = Files.readAllBytes(file);
        JsonNode response = HttpSupport.postMultipart(baseUrl + "/parse", apiKey, Map.of(),
                "file", file.getFileName().toString(), contentType(file), content);
        JsonNode blocks = response.path("blocks");
        if (!blocks.isArray()) {
            throw new IOException("版面分析返回里没有 blocks 数组：" + HttpSupport.brief(response));
        }
        return toBlocks(blocks);
    }

    /** 设置页「测试连接」用：发一个极小文本文件，能拿到 blocks 数组即视为契约对得上。 */
    public TestResult probe(Map<String, String> cfg) {
        if (HttpSupport.normalizeBase(cfg.get("baseUrl")).isEmpty()) {
            return new TestResult(false, "未填写版面分析服务地址", null);
        }
        try {
            byte[] probe = "版面分析连通性测试\n".getBytes(StandardCharsets.UTF_8);
            JsonNode response = HttpSupport.postMultipart(requireBase(cfg) + "/parse",
                    HttpSupport.text(cfg.get("apiKey")), Map.of(), "file", "probe.txt", "text/plain", probe);
            JsonNode blocks = response.path("blocks");
            if (!blocks.isArray()) {
                return new TestResult(false, "服务已响应，但未返回 blocks 数组（请核对适配层契约）",
                        HttpSupport.brief(response));
            }
            return new TestResult(true, "版面分析服务可用，返回块数 " + blocks.size(), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "调用被中断", null);
        } catch (Exception ex) {
            return new TestResult(false, "调用失败：" + (ex.getMessage() == null ? "" : ex.getMessage()), null);
        }
    }

    static List<ParsedBlock> toBlocks(JsonNode array) {
        List<ParsedBlock> blocks = new ArrayList<>();
        for (JsonNode item : array) {
            String text = item.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            blocks.add(new ParsedBlock(kindOf(item.path("kind").asText("")), text,
                    item.path("page").asInt(0), item.path("level").asInt(0)));
            if (blocks.size() >= MAX_BLOCKS) {
                break;
            }
        }
        return blocks;
    }

    static ParsedBlock.Kind kindOf(String raw) {
        if (raw == null) {
            return ParsedBlock.Kind.PARAGRAPH;
        }
        return switch (raw.trim().toUpperCase()) {
            case "HEADING", "TITLE", "SECTION_HEADER" -> ParsedBlock.Kind.HEADING;
            case "BULLET", "LIST", "LIST_ITEM" -> ParsedBlock.Kind.BULLET;
            case "TABLE_ROW", "TABLE" -> ParsedBlock.Kind.TABLE_ROW;
            default -> ParsedBlock.Kind.PARAGRAPH;
        };
    }

    private static String requireBase(Map<String, String> cfg) throws IOException {
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        if (baseUrl.isEmpty()) {
            throw new IOException("版面分析配置不完整：请填写服务地址");
        }
        return baseUrl;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }
}
