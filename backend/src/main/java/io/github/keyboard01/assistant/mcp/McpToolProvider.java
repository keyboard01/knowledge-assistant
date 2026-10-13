package io.github.keyboard01.assistant.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具提供方。抽成接口是为了让 JSON-RPC 分发器可脱离 Spring/DB 单测（手写 fake）。 */
public interface McpToolProvider {

    List<McpModels.ToolSpec> tools();

    /**
     * 调用工具。参数不合法请抛 {@link McpInvalidParamsException}（→ -32602）；
     * 其它异常视为执行失败（→ isError:true）。
     */
    McpModels.ToolResult invoke(String name, JsonNode arguments);
}
