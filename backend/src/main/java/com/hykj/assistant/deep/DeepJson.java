package com.hykj.assistant.deep;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从模型输出里抠出一个 JSON 对象。
 *
 * <p>为什么不直接 {@code readTree(整段文本)}：模型常有"好的，我来分析……{@code {...}}以上就是我的判断"
 * 这类前后文，还有用 ```json 包起来的。整段解析必然失败，而失败又会被判成"模型不会用工具"——
 * 那其实是我们的解析太脆。</p>
 *
 * <p>扫括号时必须跳过字符串内部：子问题里出现 {@code "}"}（例如引用文档里的 JSON 片段）会让
 * 朴素的"第一个 { 到第一个 }"提前收尾。</p>
 */
final class DeepJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepJson() {
    }

    /** 找第一个配平的 {@code {...}}；没有就返回 null。 */
    static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
