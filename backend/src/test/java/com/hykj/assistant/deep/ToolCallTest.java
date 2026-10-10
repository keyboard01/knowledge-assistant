package com.hykj.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 模型给的动作能不能被认出来：认错了要么白花预算，要么把整次问答带停。 */
class ToolCallTest {

    @Test
    @DisplayName("检索动作：前后带废话、带代码块都认")
    void parsesSearch() {
        ToolCall call = ToolCall
                .parse("我建议再查一次：\n```json\n{\"tool\":\"search\",\"question\":\"ALM-2201 报警\"}\n```")
                .orElseThrow();
        assertEquals(ToolCall.Action.SEARCH, call.action());
        assertEquals("ALM-2201 报警", call.question());
    }

    @Test
    @DisplayName("读取动作：文档 ID 与起始片段都带回来")
    void parsesReadDocument() {
        ToolCall call = ToolCall
                .parse("{\"tool\":\"read_document\",\"documentId\":8,\"fromChunkIndex\":11,\"limit\":6}")
                .orElseThrow();
        assertEquals(ToolCall.Action.READ_DOCUMENT, call.action());
        assertEquals(8L, call.documentId());
        assertEquals(11, call.fromChunkIndex());
        assertEquals(6, call.limit());
    }

    @Test
    @DisplayName("finish 的几种写法都认，别把'结束'当成'没看懂'")
    void parsesFinish() {
        for (String text : new String[] {"{\"tool\":\"finish\"}", "{\"tool\":\"done\"}", "{\"tool\":\"stop\"}"}) {
            assertEquals(ToolCall.Action.FINISH, ToolCall.parse(text).orElseThrow().action(), text);
        }
    }

    @Test
    @DisplayName("认不出来就返回空：调用方据此走兜底或退出，而不是抛异常给用户")
    void returnsEmptyOnGarbage() {
        for (String text : new String[] {"我想再看看", "{\"tool\":\"删除文档\"}", "{\"tool\":\"search\"}",
                "{\"tool\":\"read_document\"}", ""}) {
            assertTrue(ToolCall.parse(text).isEmpty(), "不该认出来：" + text);
        }
    }

    @Test
    @DisplayName("子问题文本里带花括号也不能把 JSON 截断")
    void keepsBracesInsideStrings() {
        ToolCall call = ToolCall.parse("{\"tool\":\"search\",\"question\":\"配置 {\\\"a\\\":1} 是什么意思\"}")
                .orElseThrow();
        assertEquals("配置 {\"a\":1} 是什么意思", call.question());
    }
}
