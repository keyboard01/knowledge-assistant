package io.github.keyboard01.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 手写 JSON-RPC 2.0 分发器（MCP Streamable HTTP 的服务端）。
 *
 * <p>刻意做成**不依赖 Spring、不依赖数据库**的纯类：协议的形状（id 回显、错误码、通知不响应）
 * 是最容易写错的部分，抽出来才能用单测钉死（沿用本仓库"手写 fake、不起 Spring 上下文"的风格）。</p>
 *
 * <p>为什么手写而不是用官方 SDK：本仓库离线构建，本地仓库里 MCP SDK 的核心 jar 缺失，拉不下来。</p>
 */
public class McpJsonRpc {

    /** 支持的最新协议版本。客户端申报版本时不校验，直接回显；申报为空则回这个。 */
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    public static final String SERVER_NAME = "knowledge-assistant";
    public static final String SERVER_VERSION = "0.1.0";

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final McpToolProvider tools;
    private final ObjectMapper mapper;

    public McpJsonRpc(McpToolProvider tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    /**
     * 处理一条 JSON-RPC 消息。
     *
     * @return 响应节点；**返回 null 表示这是通知（notification），不应产生响应**（HTTP 层回 202 空体）。
     */
    public JsonNode handle(JsonNode request) {
        if (request == null || !request.isObject()) {
            // 批量数组、标量、null：都不支持，按 invalid request（此时无法取 id，用 null）
            return error(null, INVALID_REQUEST, "只支持单个 JSON-RPC 请求对象（不接受批量数组）");
        }
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        String method = text(request.get("method"));
        if (method == null) {
            return notification ? null : error(id, INVALID_REQUEST, "缺少 method");
        }
        if (notification) {
            // 通知一律无响应（notifications/initialized 等）
            return null;
        }
        try {
            return switch (method) {
                case "initialize" -> success(id, initializeResult(request.get("params")));
                case "ping" -> success(id, mapper.createObjectNode());
                case "tools/list" -> success(id, toolsListResult());
                case "tools/call" -> toolsCall(id, request.get("params"));
                default -> error(id, METHOD_NOT_FOUND, "未知方法：" + method);
            };
        } catch (McpInvalidParamsException ex) {
            return error(id, INVALID_PARAMS, ex.getMessage());
        } catch (Exception ex) {
            return error(id, INTERNAL_ERROR, "内部错误：" + describe(ex));
        }
    }

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? text(params.get("protocolVersion")) : null;
        String version = requested == null || requested.isBlank() ? LATEST_PROTOCOL_VERSION : requested;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", mapper.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode info = mapper.createObjectNode();
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        result.set("serverInfo", info);
        return result;
    }

    private ObjectNode toolsListResult() {
        ArrayNode array = mapper.createArrayNode();
        for (McpModels.ToolSpec spec : tools.tools()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", spec.name());
            node.put("description", spec.description() == null ? "" : spec.description());
            node.set("inputSchema", spec.inputSchema() == null ? mapper.createObjectNode() : spec.inputSchema());
            array.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", array);
        return result;
    }

    private JsonNode toolsCall(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject() || !params.hasNonNull("name")) {
            throw new McpInvalidParamsException("tools/call 缺少参数 name");
        }
        String name = text(params.get("name"));
        boolean known = tools.tools().stream().anyMatch(spec -> spec.name().equals(name));
        if (!known) {
            throw new McpInvalidParamsException("未知工具：" + name);
        }
        try {
            McpModels.ToolResult result = tools.invoke(name, params.get("arguments"));
            return success(id, toolCallResult(result));
        } catch (McpInvalidParamsException ex) {
            throw ex;
        } catch (Exception ex) {
            // 工具执行失败：HTTP 200 + isError:true（不是协议错误，也不是 5xx）
            return success(id, toolCallResult(McpModels.ToolResult.error("工具执行失败：" + describe(ex))));
        }
    }

    private ObjectNode toolCallResult(McpModels.ToolResult result) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", result.text() == null ? "" : result.text());
        ObjectNode content = mapper.createObjectNode();
        ArrayNode array = mapper.createArrayNode();
        array.add(block);
        content.set("content", array);
        content.put("isError", result.isError());
        return content;
    }

    public ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode node = envelope(id);
        node.set("result", result == null ? mapper.createObjectNode() : result);
        return node;
    }

    public ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode node = envelope(id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        node.set("error", error);
        return node;
    }

    private ObjectNode envelope(JsonNode id) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            node.putNull("id");
        } else {
            node.set("id", id);
        }
        return node;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return value == null || value.isBlank() ? null : value;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
