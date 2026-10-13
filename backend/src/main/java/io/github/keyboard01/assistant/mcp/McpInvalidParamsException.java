package io.github.keyboard01.assistant.mcp;

/**
 * 工具参数不合法（缺必需参数、类型不对）。由 JSON-RPC 层映射成 {@code -32602}，
 * 与"工具执行失败"区分开——后者是 {@code isError:true} 的结果，不是协议错误。
 */
public class McpInvalidParamsException extends RuntimeException {

    public McpInvalidParamsException(String message) {
        super(message);
    }
}
