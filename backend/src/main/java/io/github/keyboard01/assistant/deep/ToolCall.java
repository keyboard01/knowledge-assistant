package io.github.keyboard01.assistant.deep;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * S6 里模型请求的一次工具调用。
 *
 * <p>参数只有三个字段，而且**没有身份、没有知识域、没有过滤条件**——那些由服务端在实现里补全
 * （设计文档 §4.5：用户身份、租户、可见范围不进入模型可填写的参数）。模型就算在 JSON 里自己写上
 * {@code "domainCode":"policy"}，也只会被当成不认识的字段丢掉。</p>
 */
public record ToolCall(Action action, String question, Long documentId, Integer fromChunkIndex, Integer limit) {

    public enum Action {
        SEARCH, READ_DOCUMENT, FINISH
    }

    public static ToolCall finish() {
        return new ToolCall(Action.FINISH, null, null, null, null);
    }

    public static ToolCall search(String question) {
        return new ToolCall(Action.SEARCH, question, null, null, null);
    }

    public static ToolCall readDocument(long documentId, Integer fromChunkIndex, Integer limit) {
        return new ToolCall(Action.READ_DOCUMENT, null, documentId, fromChunkIndex, limit);
    }

    /** 解析失败返回空：调用方据此走"确定性补齐"或退出，而不是把异常冒到用户面前。 */
    public static Optional<ToolCall> parse(String modelText) {
        String json = DeepJson.extractObject(modelText);
        if (json == null) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = DeepJson.MAPPER.readTree(json);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String tool = root.path("tool").asText("").toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        switch (tool) {
            case "finish", "done", "stop", "answer":
                return Optional.of(finish());
            case "search", "retrieval", "retrieve":
                String question = root.path("question").asText("");
                return question.isBlank() ? Optional.empty() : Optional.of(search(question.strip()));
            case "readdocument", "read", "readchapter", "expand":
                JsonNode id = root.path("documentId");
                if (!id.canConvertToLong()) {
                    return Optional.empty();
                }
                return Optional.of(readDocument(id.asLong(), intOrNull(root.path("fromChunkIndex")),
                        intOrNull(root.path("limit"))));
            default:
                return Optional.empty();
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : null;
    }
}
