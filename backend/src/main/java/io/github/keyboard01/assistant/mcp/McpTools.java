package io.github.keyboard01.assistant.mcp;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.keyboard01.assistant.domain.DomainService;
import io.github.keyboard01.assistant.media.MediaModels;
import io.github.keyboard01.assistant.media.MediaSearchService;
import io.github.keyboard01.assistant.retrieval.Evidence;
import io.github.keyboard01.assistant.retrieval.RetrievalResult;
import io.github.keyboard01.assistant.retrieval.RetrievalService;
import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.settings.SettingService;
import io.github.keyboard01.assistant.view.DocumentAccessDeniedException;
import io.github.keyboard01.assistant.view.DocumentViewModels.ContentView;
import io.github.keyboard01.assistant.view.DocumentViewService;

/**
 * MCP 的三个工具，全部走既有服务、用 {@link CurrentUser} 的身份：
 * 可见范围与问答完全一致（域级 + 文档级 ACL + 现行状态），MCP 不是第二条权限路径。
 */
@Component
public class McpTools implements McpToolProvider {

    private static final int MAX_TOOL_TEXT = 20_000;
    private static final int FRAGMENT_PREVIEW = 600;
    private static final int DEFAULT_READ_CHARS = 8_000;

    private final DomainService domainService;
    private final RetrievalService retrievalService;
    private final DocumentViewService documentViewService;
    private final MediaSearchService mediaSearchService;
    private final SettingService settingService;
    private final ObjectMapper mapper;
    private final List<McpModels.ToolSpec> specs;

    public McpTools(DomainService domainService, RetrievalService retrievalService,
                    DocumentViewService documentViewService, MediaSearchService mediaSearchService,
                    SettingService settingService, ObjectMapper mapper) {
        this.domainService = domainService;
        this.retrievalService = retrievalService;
        this.documentViewService = documentViewService;
        this.mediaSearchService = mediaSearchService;
        this.settingService = settingService;
        this.mapper = mapper;
        this.specs = List.of(searchSpec(), listDomainsSpec(), readDocumentSpec(), searchImagesSpec());
    }

    @Override
    public List<McpModels.ToolSpec> tools() {
        return specs;
    }

    @Override
    public McpModels.ToolResult invoke(String name, JsonNode arguments) {
        return switch (name) {
            case "search_documents" -> search(arguments);
            case "list_domains" -> listDomains();
            case "read_document" -> readDocument(arguments);
            case "search_images" -> searchImages(arguments);
            default -> throw new McpInvalidParamsException("未知工具：" + name);
        };
    }

    private McpModels.ToolResult search(JsonNode args) {
        String domainCode = requireText(args, "domainCode");
        String question = requireText(args, "question");
        Integer topN = optionalInt(args, "topN");
        Double minScore = optionalDouble(args, "minScore");
        try {
            RetrievalResult result = retrievalService.search(CurrentUser.subject(), domainCode, question, topN,
                    minScore);
            return McpModels.ToolResult.ok(truncate(renderRetrieval(result), MAX_TOOL_TEXT));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检索被中断");
        } catch (Exception ex) {
            throw new IllegalStateException(describe(ex));
        }
    }

    private McpModels.ToolResult listDomains() {
        List<DomainService.DomainView> domains = domainService.list(CurrentUser.subject());
        if (domains.isEmpty()) {
            return McpModels.ToolResult.ok("当前身份没有可见的知识域。");
        }
        StringBuilder sb = new StringBuilder("可见知识域：\n");
        for (DomainService.DomainView domain : domains) {
            sb.append("- ").append(domain.code()).append("（").append(domain.name()).append("）")
                    .append(domain.enabled() ? "" : " [已停用]")
                    .append("，可见文档 ").append(domain.visibleDocuments()).append(" 份\n");
        }
        return McpModels.ToolResult.ok(sb.toString());
    }

    private McpModels.ToolResult readDocument(JsonNode args) {
        long documentId = requireLong(args, "documentId");
        Integer requested = optionalInt(args, "maxChars");
        int maxChars = requested == null ? DEFAULT_READ_CHARS : Math.max(200, requested);
        try {
            ContentView content = documentViewService.content(documentId, "mcp");
            StringBuilder sb = new StringBuilder();
            sb.append(content.title()).append("（").append(content.docNo()).append(' ')
                    .append(content.version()).append("，文档id=").append(content.documentId()).append("）\n\n");
            sb.append(content.markdown() == null ? "" : content.markdown());
            return McpModels.ToolResult.ok(truncate(sb.toString(), Math.min(maxChars, MAX_TOOL_TEXT)));
        } catch (DocumentAccessDeniedException ex) {
            // 不存在与无权访问返回同一种结果，不暴露存在性（与查看路径一致）
            return McpModels.ToolResult.error("无权访问该文档，或文档不存在/已非现行。");
        }
    }

    private McpModels.ToolResult searchImages(JsonNode args) {
        String domainCode = requireText(args, "domainCode");
        String query = requireText(args, "query");
        Integer topN = optionalInt(args, "topN");
        try {
            MediaModels.SearchResult result = mediaSearchService.search(CurrentUser.subject(), domainCode, query,
                    topN, null, settingService.effective(SettingService.GROUP_IMAGE_EMBEDDING));
            if (result.hits().isEmpty()) {
                return McpModels.ToolResult.ok("没有找到匹配的图片。");
            }
            StringBuilder sb = new StringBuilder("匹配的图片：\n");
            for (MediaModels.Hit hit : result.hits()) {
                sb.append("- assetId=").append(hit.assetId())
                        .append(" 文档《").append(hit.title()).append("》（")
                        .append(hit.docNo()).append(' ').append(hit.version()).append("）");
                if (hit.page() != null) {
                    sb.append(" 第 ").append(hit.page()).append(" 页");
                }
                sb.append("\n  描述：").append(truncate(hit.caption() == null ? "" : hit.caption(), 200));
                sb.append("\n  原图：/api/media/").append(hit.assetId()).append("/raw\n");
            }
            return McpModels.ToolResult.ok(truncate(sb.toString(), MAX_TOOL_TEXT));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图片检索被中断");
        } catch (Exception ex) {
            throw new IllegalStateException(describe(ex));
        }
    }

    private String renderRetrieval(RetrievalResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.insufficient()) {
            sb.append("证据不足，未能给出结论。\n");
        }
        if (result.accessNotice() != null && !result.accessNotice().isBlank()) {
            sb.append(result.accessNotice()).append('\n');
        }
        if (result.evidences().isEmpty()) {
            sb.append("未检索到当前身份可见的证据。");
            return sb.toString();
        }
        for (Evidence evidence : result.evidences()) {
            sb.append('#').append(evidence.rank()).append(' ').append(evidence.title())
                    .append("（").append(evidence.docNo()).append(' ').append(evidence.version())
                    .append("，文档id=").append(evidence.documentId()).append("）\n");
            for (Evidence.Fragment fragment : evidence.fragments()) {
                sb.append("- [").append(fragment.chunkId()).append("] ");
                if (fragment.pageFrom() != null) {
                    sb.append("p").append(fragment.pageFrom()).append(' ');
                }
                sb.append(truncate(fragment.text() == null ? "" : fragment.text().strip(), FRAGMENT_PREVIEW))
                        .append('\n');
            }
        }
        if (result.hint() != null && !result.hint().isBlank()) {
            sb.append("\n提示：").append(result.hint());
        }
        return sb.toString();
    }

    private McpModels.ToolSpec searchSpec() {
        ObjectNode schema = schema(new String[] {"domainCode", "question"});
        prop(schema, "domainCode", "string", "知识域代码；先调 list_domains 获取");
        prop(schema, "question", "string", "检索问题");
        prop(schema, "topN", "integer", "返回条数，默认 5");
        prop(schema, "minScore", "number", "召回下限，0~1");
        return new McpModels.ToolSpec("search_documents", "在当前身份可见范围内检索并返回带出处的证据片段",
                schema);
    }

    private McpModels.ToolSpec listDomainsSpec() {
        return new McpModels.ToolSpec("list_domains", "列出当前身份可见的知识域", schema(new String[0]));
    }

    private McpModels.ToolSpec readDocumentSpec() {
        ObjectNode schema = schema(new String[] {"documentId"});
        prop(schema, "documentId", "integer", "文档 id（可从 search_documents 的结果里取）");
        prop(schema, "maxChars", "integer", "返回正文的最大字符数，默认 8000");
        return new McpModels.ToolSpec("read_document", "按 id 读取原文（会校验域级/文档级权限与现行状态）", schema);
    }

    private McpModels.ToolSpec searchImagesSpec() {
        ObjectNode schema = schema(new String[] {"domainCode", "query"});
        prop(schema, "domainCode", "string", "知识域代码；先调 list_domains 获取");
        prop(schema, "query", "string", "要找的图片描述，如“设备结构示意图”“带公司 logo 的封面”");
        prop(schema, "topN", "integer", "返回条数，默认 8");
        return new McpModels.ToolSpec("search_images", "按描述检索知识域里的图片素材（只含可见且已生效的文档）",
                schema);
    }

    private ObjectNode schema(String[] required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        ArrayNode requiredArray = mapper.createArrayNode();
        for (String name : required) {
            requiredArray.add(name);
        }
        schema.set("required", requiredArray);
        return schema;
    }

    private void prop(ObjectNode schema, String name, String type, String description) {
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ObjectNode property = properties.putObject(name);
        property.put("type", type);
        property.put("description", description);
    }

    private static String requireText(JsonNode args, String name) {
        if (args == null || !args.hasNonNull(name) || args.get(name).asText().isBlank()) {
            throw new McpInvalidParamsException("缺少必需参数：" + name);
        }
        return args.get(name).asText().trim();
    }

    private static long requireLong(JsonNode args, String name) {
        if (args == null || !args.hasNonNull(name) || !args.get(name).canConvertToLong()) {
            throw new McpInvalidParamsException("缺少或非法的参数：" + name);
        }
        return args.get(name).asLong();
    }

    private static Integer optionalInt(JsonNode args, String name) {
        return args != null && args.hasNonNull(name) && args.get(name).isNumber() ? args.get(name).asInt() : null;
    }

    private static Double optionalDouble(JsonNode args, String name) {
        return args != null && args.hasNonNull(name) && args.get(name).isNumber() ? args.get(name).asDouble() : null;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…（已截断）";
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
