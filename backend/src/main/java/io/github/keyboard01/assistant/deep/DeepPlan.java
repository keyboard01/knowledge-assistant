package io.github.keyboard01.assistant.deep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.keyboard01.assistant.deep.DeepModels.SubQuestion;

/**
 * S5 的产出：子问题列表。
 *
 * <p>这个类里全是**纯函数**，因为"模型输出什么形状"是最不可控的一环：拆解得对不对要靠模型，
 * 但"拆出来的东西能不能用"必须由我们决定——所以校验、去重、截断、退化都收在这里，一处可单测。</p>
 */
public final class DeepPlan {

    /**
     * 拆解结果。{@code needClarification} 只是模型的一条"我认为问法缺限定"的提示：真正要不要反问，
     * 由 {@link GapJudge} 在**查过一轮仍没有证据**之后决定——限定条件常常就写在知识库里。
     */
    public record Plan(boolean needClarification, String clarifyQuestion, List<SubQuestion> subQuestions) {

        public Plan {
            subQuestions = List.copyOf(subQuestions);
        }

        public boolean single() {
            return subQuestions.size() == 1;
        }

        public boolean empty() {
            return subQuestions.isEmpty();
        }
    }

    /** 澄清话术的兜底：模型说了"要澄清"却没给话术时用，总比问一句空话强。 */
    static final String DEFAULT_CLARIFY = "请补充型号、版本或适用范围等信息，我再据此检索。";

    private DeepPlan() {
    }

    public static Plan single(String question) {
        return new Plan(false, null, List.of(new SubQuestion("Q1", question, "原问题（未拆解）")));
    }

    /**
     * 规划失败时的退化：把原问题当成唯一子问题。
     *
     * <p>刻意不做"再问一次模型"：规划是一种增强，不是前提。让它失败即降级为单轮问答，
     * 用户拿到的结果与快速模式一致，而不是一个错误页。</p>
     */
    public static Plan fallback(String question) {
        return single(question);
    }

    /**
     * 解析模型输出。
     *
     * @param maxSubQuestions 上限，超出的**丢弃**而不是报错：多拆两条子问题不会让答案变差，
     *                        但会让成本与耗时翻倍——这类"悄悄地多花钱"必须在解析层就掐掉。
     */
    public static Plan parse(String modelText, String question, int maxSubQuestions) {
        String json = DeepJson.extractObject(modelText);
        if (json == null) {
            return fallback(question);
        }
        JsonNode root;
        try {
            root = DeepJson.MAPPER.readTree(json);
        } catch (Exception ex) {
            return fallback(question);
        }
        if (root == null || !root.isObject()) {
            return fallback(question);
        }
        boolean clarify = root.path("needClarification").asBoolean(false);
        String clarifyQuestion = text(root.path("clarifyQuestion"));
        if (clarify && (clarifyQuestion == null || clarifyQuestion.isBlank())) {
            clarifyQuestion = DEFAULT_CLARIFY;
        }
        List<SubQuestion> subQuestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode array = root.path("subQuestions");
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (subQuestions.size() >= maxSubQuestions) {
                    break;
                }
                String asked = node.isTextual() ? node.asText() : text(node.path("question"));
                if (asked == null || asked.isBlank()) {
                    continue;
                }
                if (!seen.add(normalize(asked))) {
                    continue; // 同一个子问题问两遍 = 同一份证据拿两遍，纯浪费
                }
                String why = node.isTextual() ? null : text(node.path("why"));
                subQuestions.add(new SubQuestion("Q" + (subQuestions.size() + 1), asked.strip(),
                        why == null ? "" : why.strip()));
            }
        }
        if (clarify) {
            // 说了要澄清却一条子问题都没给：仍然按原问题查一轮。澄清是"先查后问"的最后一步
            // （见 GapJudge），在这里就把探索整段跳过，本来答得上来的问题会直接变成一句反问。
            List<SubQuestion> asked = subQuestions.isEmpty()
                    ? List.of(new SubQuestion("Q1", question, "原问题（模型认为需要澄清）"))
                    : subQuestions;
            return new Plan(true, clarifyQuestion, asked);
        }
        if (subQuestions.isEmpty()) {
            return fallback(question);
        }
        return new Plan(false, null, subQuestions);
    }

    /** 去重口径：空白与中英文标点都不算差别，"GA-75 保养周期？"与"GA-75保养周期"是同一个问题。 */
    static String normalize(String text) {
        return text.replaceAll("[\\s\\p{Punct}，。；：！？、（）《》“”‘’【】]", "").toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }
}
