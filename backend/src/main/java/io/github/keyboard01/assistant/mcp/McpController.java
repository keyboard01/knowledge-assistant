package io.github.keyboard01.assistant.mcp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP Streamable HTTP 端点：{@code POST /api/mcp}。
 *
 * <p>身份来自 {@code Authorization: Bearer <API Key>}（由 {@code AuthInterceptor} 解析），
 * 因此落在 {@code /api/**} 的默认拒绝之内，无需改 WebConfig。</p>
 *
 * <p><b>错误全部在此层内自行格式化</b>：本应用开了 {@code spring.mvc.problemdetails}，
 * 一旦异常漏到 Spring 异常处理，就会返回 RFC7807 文本而不是 JSON-RPC 的 {@code {error:{code,message}}}。</p>
 */
@RestController
public class McpController {

    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=utf-8");

    private final McpJsonRpc rpc;
    private final ObjectMapper mapper;

    public McpController(McpToolProvider tools, ObjectMapper mapper) {
        this.rpc = new McpJsonRpc(tools, mapper);
        this.mapper = mapper;
    }

    /**
     * 请求体显式收成 String 再自行解析：这样"JSON 语法错误"能回一个规范的 {@code -32700}，
     * 而不是让 Spring 的消息转换器抛 400。
     */
    @PostMapping("/api/mcp")
    public ResponseEntity<String> post(@RequestBody(required = false) String body) {
        JsonNode request;
        try {
            request = body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (Exception ex) {
            return json(rpc.error(null, McpJsonRpc.PARSE_ERROR, "无法解析 JSON-RPC 请求：" + ex.getMessage()));
        }
        JsonNode response = rpc.handle(request);
        if (response == null) {
            // 通知（如 notifications/initialized）：必须 202 且空体，不是 200 {}
            return ResponseEntity.status(202).build();
        }
        return json(response);
    }

    private ResponseEntity<String> json(JsonNode node) {
        try {
            return ResponseEntity.ok().contentType(JSON).body(mapper.writeValueAsString(node));
        } catch (Exception ex) {
            String fallback = "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32603,\"message\":\"序列化响应失败\"}}";
            return ResponseEntity.ok().contentType(JSON).body(fallback);
        }
    }
}
