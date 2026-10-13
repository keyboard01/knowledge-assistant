package io.github.keyboard01.assistant.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** MCP 工具与结果的对外模型。 */
public final class McpModels {

    private McpModels() {
    }

    /** 一个工具的声明：名称、说明、输入 JSON Schema。 */
    public record ToolSpec(String name, String description, ObjectNode inputSchema) {
    }

    /** 工具执行结果。{@code isError=true} 表示执行失败（证据不足/权限/后端故障），不是协议错误。 */
    public record ToolResult(boolean isError, String text) {

        public static ToolResult ok(String text) {
            return new ToolResult(false, text);
        }

        public static ToolResult error(String text) {
            return new ToolResult(true, text);
        }
    }
}
