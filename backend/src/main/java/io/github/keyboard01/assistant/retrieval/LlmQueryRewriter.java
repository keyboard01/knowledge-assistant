package io.github.keyboard01.assistant.retrieval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.keyboard01.assistant.generation.LanguageModel;

/**
 * {@link QueryRewriter} 的 LLM 实现（P1）。开关 {@code assistant.retrieval.rewrite}（默认 off）。
 *
 * <p>提示词第 2 条（型号/编号/版本号/时间范围原样保留）与 {@code deep/QuestionDecomposer} 的约定一致：
 * 这类词一旦被"意译"，精确命中就没了——而关键词通道恰恰靠它们把答案行顶上来。</p>
 *
 * <p>解析刻意容错：模型常把 JSON 包在 ```json 里、或前后带一句解释，所以先扫出第一个配平的
 * JSON 数组再解析（与 {@code DeepJson} 同思路）。解析不出就返回空表——宁可只用原问题，也不赌半个变体。</p>
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    /** 最多产出的变体条数。多了只会让成本与噪声一起涨。 */
    static final int MAX_VARIANTS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM = """
            你是企业知识库的检索查询改写器。把用户问题改写成 %d 条**更适合检索**的说法，
            用来在制度/技术/维修文档里找到对应内容。

            规则：
            1. 保持原问题的意图不变，只是换一种更接近正式文档的表述（例如口语"停机了怎么办" → "设备故障停机上报流程"）。
            2. 型号、编号、版本号、时间范围一律**原样保留**，不要改写、不要增删。
            3. 每条都能脱离原问题单独检索，不要写成"同上一条"这类依赖关系。
            4. 只输出一个 JSON 字符串数组，不要解释，不要用代码块：["...", "..."]
            """;

    private final LanguageModel languageModel;
    private final boolean enabled;

    public LlmQueryRewriter(LanguageModel languageModel,
                            @Value("${assistant.retrieval.rewrite:off}") String mode) {
        this.languageModel = languageModel;
        this.enabled = mode != null && "on".equalsIgnoreCase(mode.trim());
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<String> rewrite(String question) throws IOException, InterruptedException {
        LanguageModel.Completion completion =
                languageModel.complete(SYSTEM.formatted(MAX_VARIANTS), "【用户问题】\n" + question);
        return parse(completion.content(), question);
    }

    /** 解析模型输出里的字符串数组；去掉与原问题相同、空串与重复项，最多 {@link #MAX_VARIANTS} 条。 */
    static List<String> parse(String content, String original) {
        JsonNode array = readArray(content);
        List<String> variants = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return variants;
        }
        for (JsonNode node : array) {
            String value = node.asText("").trim();
            if (value.isEmpty() || value.equals(original) || variants.contains(value)) {
                continue;
            }
            variants.add(value);
            if (variants.size() >= MAX_VARIANTS) {
                break;
            }
        }
        return variants;
    }

    /**
     * 从模型输出里抠出第一个配平的 JSON 数组；没有就返回 null。
     *
     * <p>两遍扫描且都跳过字符串内部：第一遍找**不在字符串里**的第一个 {@code [}（否则前面解释文字里的
     * {@code "[1]"} 会被当成数组开头），第二遍从那里配平到对应的 {@code ]}。</p>
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

    /** 找第一个不在字符串里的 {@code [}；没有返回 -1。 */
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
        return "LLM 查询改写";
    }
}
