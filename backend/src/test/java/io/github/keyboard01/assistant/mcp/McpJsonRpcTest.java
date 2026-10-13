package io.github.keyboard01.assistant.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 分发器的形状测试。手写 fake，不启 Spring 上下文（沿用本仓库测试风格）。
 */
class McpJsonRpcTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个正常工具 echo + 一个执行必炸的 boom，用来区分"参数错误"与"执行失败"。 */
    private static final class FakeTools implements McpToolProvider {

        @Override
        public List<McpModels.ToolSpec> tools() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            return List.of(new McpModels.ToolSpec("echo", "回显", schema),
                    new McpModels.ToolSpec("boom", "总是失败", schema));
        }

        @Override
        public McpModels.ToolResult invoke(String name, JsonNode arguments) {
            switch (name) {
                case "echo":
                    String text = arguments == null ? "" : arguments.path("text").asText("");
                    if (text.isBlank()) {
                        throw new McpInvalidParamsException("缺少参数 text");
                    }
                    return McpModels.ToolResult.ok("echo:" + text);
                case "boom":
                    throw new IllegalStateException("后端炸了");
                default:
                    throw new McpInvalidParamsException("未知工具：" + name);
            }
        }
    }

    private final McpJsonRpc rpc = new McpJsonRpc(new FakeTools(), MAPPER);

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("initialize：回显协议版本，capabilities 与 serverInfo 齐备")
    void initializeEchoesProtocolVersion() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""));
        assertEquals("2.0", res.get("jsonrpc").asText());
        assertEquals(1, res.get("id").asInt());
        JsonNode result = res.get("result");
        assertEquals("2025-06-18", result.get("protocolVersion").asText());
        assertTrue(result.get("capabilities").has("tools"));
        assertEquals(McpJsonRpc.SERVER_NAME, result.get("serverInfo").get("name").asText());
    }

    @Test
    @DisplayName("initialize：客户端未申报版本时回最新支持版本")
    void initializeDefaultsVersion() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""));
        assertEquals(McpJsonRpc.LATEST_PROTOCOL_VERSION, res.get("result").get("protocolVersion").asText());
    }

    @Test
    @DisplayName("通知：不产生响应（返回 null）")
    void notificationsProduceNoResponse() throws Exception {
        assertNull(rpc.handle(parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        assertNull(rpc.handle(parse("{\"jsonrpc\":\"2.0\",\"method\":\"some/random/notify\"}")));
    }

    @Test
    @DisplayName("ping：空结果")
    void pingReturnsEmptyResult() throws Exception {
        JsonNode res = rpc.handle(parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"));
        assertEquals(2, res.get("id").asInt());
        assertTrue(res.get("result").isObject());
        assertEquals(0, res.get("result").size());
    }

    @Test
    @DisplayName("tools/list：返回工具且每个都带 inputSchema")
    void toolsListHasSchemas() throws Exception {
        JsonNode res = rpc.handle(parse("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"));
        JsonNode tools = res.get("result").get("tools");
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).has("name"));
        assertTrue(tools.get(0).has("inputSchema"));
    }

    @Test
    @DisplayName("tools/call：成功返回 content + isError=false")
    void toolCallSuccess() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"echo","arguments":{"text":"hi"}}}"""));
        JsonNode result = res.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        assertEquals("text", result.get("content").get(0).get("type").asText());
        assertEquals("echo:hi", result.get("content").get(0).get("text").asText());
    }

    @Test
    @DisplayName("tools/call：工具执行失败 → 结果为 isError=true，不是协议错误")
    void toolExecutionFailureIsResultNotError() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom","arguments":{}}}"""));
        assertNull(res.get("error"));
        assertTrue(res.get("result").get("isError").asBoolean());
        assertTrue(res.get("result").get("content").get(0).get("text").asText().contains("后端炸了"));
    }

    @Test
    @DisplayName("tools/call：缺必需参数 → -32602")
    void toolCallMissingParamIsInvalidParams() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"echo","arguments":{}}}"""));
        assertEquals(McpJsonRpc.INVALID_PARAMS, res.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("tools/call：未知工具 → -32602")
    void unknownToolIsInvalidParams() throws Exception {
        JsonNode res = rpc.handle(parse("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""));
        assertEquals(McpJsonRpc.INVALID_PARAMS, res.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("未知方法 → -32601")
    void unknownMethod() throws Exception {
        JsonNode res = rpc.handle(parse("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/list\"}"));
        assertEquals(McpJsonRpc.METHOD_NOT_FOUND, res.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("非对象请求（批量数组）→ -32600，id 为 null")
    void batchOrScalarIsInvalidRequest() throws Exception {
        JsonNode res = rpc.handle(parse("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"));
        assertEquals(McpJsonRpc.INVALID_REQUEST, res.get("error").get("code").asInt());
        assertTrue(res.get("id").isNull());
    }

    @Test
    @DisplayName("null 请求 → -32600")
    void nullRequestIsInvalid() {
        JsonNode res = rpc.handle(null);
        assertEquals(McpJsonRpc.INVALID_REQUEST, res.get("error").get("code").asInt());
    }
}
