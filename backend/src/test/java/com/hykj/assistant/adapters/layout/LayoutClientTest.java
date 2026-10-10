package com.hykj.assistant.adapters.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hykj.assistant.parse.ParsedBlock;

/**
 * 版面分析返回的映射是纯函数，钉住它：kind 不认识要退化成 PARAGRAPH（丢块比丢信息更糟），
 * 空白块要丢掉（空块进了切分只会污染检索）。
 */
class LayoutClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("kind 映射：认识的分类对上，不认识的一律当 PARAGRAPH")
    void mapsKind() {
        assertEquals(ParsedBlock.Kind.HEADING, LayoutClient.kindOf("heading"));
        assertEquals(ParsedBlock.Kind.HEADING, LayoutClient.kindOf("TITLE"));
        assertEquals(ParsedBlock.Kind.BULLET, LayoutClient.kindOf("list_item"));
        assertEquals(ParsedBlock.Kind.TABLE_ROW, LayoutClient.kindOf("table"));
        assertEquals(ParsedBlock.Kind.PARAGRAPH, LayoutClient.kindOf("figure_caption"));
        assertEquals(ParsedBlock.Kind.PARAGRAPH, LayoutClient.kindOf(null));
    }

    @Test
    @DisplayName("块数组映射：空白块丢弃，页码/层级取回，缺省补 0")
    void mapsBlocks() throws Exception {
        JsonNode array = MAPPER.readTree("""
                [{"kind":"heading","text":"第一章","page":1,"level":1},
                 {"kind":"table_row","text":"行1","page":2,"level":0},
                 {"kind":"","text":"普通段","page":2},
                 {"kind":"bullet","text":"   "}]
                """);

        List<ParsedBlock> blocks = LayoutClient.toBlocks(array);

        assertEquals(3, blocks.size(), "空白块被丢弃");
        assertEquals(ParsedBlock.Kind.HEADING, blocks.get(0).kind());
        assertEquals(1, blocks.get(0).page());
        assertEquals(1, blocks.get(0).level());
        assertEquals(ParsedBlock.Kind.TABLE_ROW, blocks.get(1).kind());
        assertEquals(ParsedBlock.Kind.PARAGRAPH, blocks.get(2).kind());
        assertEquals(0, blocks.get(2).level(), "缺省层级补 0");
    }
}
