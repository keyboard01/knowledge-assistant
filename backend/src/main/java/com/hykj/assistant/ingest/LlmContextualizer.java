package com.hykj.assistant.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hykj.assistant.generation.LanguageModel;
import com.hykj.assistant.parse.ParsedChunk;
import com.hykj.assistant.settings.SettingService;

/**
 * {@link Contextualizer} 的 LLM 实现（P1）。开关取「文档同步」分组的 {@code contextual}（默认 false）。
 *
 * <p>分批（{@link #BATCH_SIZE} 段一次）调用生成模型：一批一次请求，既省调用次数，又避免把整份文档
 * 塞进一次请求。**任一批失败只让该批降级为空串**，不影响其它批，也不阻断入库。</p>
 */
@Component
public class LlmContextualizer implements Contextualizer {

    private static final Logger log = LoggerFactory.getLogger(LlmContextualizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单次请求塞几条片段：太多模型容易"只看前几条"，太少则调用次数涨。 */
    static final int BATCH_SIZE = 8;

    static final String SYSTEM = """
            你是企业知识库的入库助手。下面会给出一份文档里的若干编号片段。
            为**每一条**片段写一句不超过 60 字的上下文，说明它讲的是什么、属于文档的哪个主题，
            使这条片段脱离原文也能被检索到。

            规则：
            1. 只依据片段本身与给出的文档标题、章节路径，不要臆造片段里没有的信息；判断不了就写空字符串。
            2. 不要照抄片段原文，用一句话概括。
            3. 用简体中文。
            4. 只输出一个 JSON 字符串数组，长度必须等于片段条数，第 i 个元素对应第 i 条片段；
               不要解释，不要用代码块。
            """;

    private final SettingService settingService;
    private final LanguageModel languageModel;

    public LlmContextualizer(SettingService settingService, LanguageModel languageModel) {
        this.settingService = settingService;
        this.languageModel = languageModel;
    }

    @Override
    public boolean enabled() {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_INGEST);
        String value = cfg.get("contextual");
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public List<String> contexts(String documentTitle, List<ParsedChunk> chunks)
            throws IOException, InterruptedException {
        List<String> contexts = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            int end = Math.min(chunks.size(), start + BATCH_SIZE);
            contexts.addAll(batch(documentTitle, chunks.subList(start, end)));
        }
        return contexts;
    }

    /** 一批：生成失败降级为空串；只有中断往外抛（由上层决定中止还是继续）。 */
    private List<String> batch(String documentTitle, List<ParsedChunk> batch) throws InterruptedException {
        try {
            LanguageModel.Completion completion = languageModel.complete(SYSTEM, user(documentTitle, batch));
            return align(completion.content(), batch.size());
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("片段上下文生成失败，本批 {} 段按无上下文入库（fail-open）：{}", batch.size(), ex.toString());
            return blank(batch.size());
        }
    }

    /** 拼用户提示词：标题 + 逐条「序号. 章节路径 / 正文」。 */
    static String user(String documentTitle, List<ParsedChunk> batch) {
        StringBuilder builder = new StringBuilder();
        builder.append("【文档标题】").append(documentTitle == null ? "" : documentTitle).append('\n');
        builder.append("【片段】\n");
        for (int i = 0; i < batch.size(); i++) {
            ParsedChunk chunk = batch.get(i);
            builder.append(i + 1).append(". 章节路径：")
                    .append(chunk.sectionPath() == null ? "(无)" : chunk.sectionPath()).append('\n');
            builder.append("   正文：").append(chunk.content()).append('\n');
        }
        return builder.toString();
    }

    /** 把模型输出的数组按位对齐到 expected 长度；缺失/多余/解析失败都以空串占位。 */
    static List<String> align(String content, int expected) {
        JsonNode array = readArray(content);
        List<String> contexts = blank(expected);
        if (array == null || !array.isArray()) {
            return contexts;
        }
        for (int i = 0; i < expected && i < array.size(); i++) {
            contexts.set(i, array.get(i).asText("").trim());
        }
        return contexts;
    }

    private static List<String> blank(int size) {
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add("");
        }
        return values;
    }

    /**
     * 抠出第一个配平的 JSON 数组；两遍扫描都跳过字符串内部，
     * 避免片段正文里出现的 {@code [} / {@code ]} 或 {@code "[1]"} 被误当成数组边界。
     */
    static JsonNode readArray(String text) {
        if (text == null) {
            return null;
        }
        int start = firstArrayStart(text);
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
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    try {
                        return MAPPER.readTree(text.substring(start, i + 1));
                    } catch (Exception ex) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int firstArrayStart(String text) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
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
            } else if (c == '[') {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String displayName() {
        return "LLM 片段上下文";
    }
}
