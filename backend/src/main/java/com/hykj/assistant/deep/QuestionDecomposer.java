package com.hykj.assistant.deep;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hykj.assistant.generation.LanguageModel;

/**
 * S5：把问题拆成可验证的子问题。
 *
 * <p>提示词里"不要为了拆而拆"的几条规则是从 §14 的实验反推的：真正的收益来自**把聚合类问题
 * （一共分几档 / 哪些 / 两边各自）拆成逐条追问**——那样每一条的检索词才足够具体，表行才有机会
 * 挤进各自的前几名。单一事实问题拆成三条只会让成本翻三倍。</p>
 */
@Component
public class QuestionDecomposer {

    private static final Logger log = LoggerFactory.getLogger(QuestionDecomposer.class);

    static final String SYSTEM = """
            你是企业知识助手的检索规划器。你的任务是判断：用户的问题需要哪几条**能被知识库单独检索**的子问题。

            只输出一个 JSON 对象，不要解释，不要用代码块：
            {"needClarification": false, "clarifyQuestion": "", "subQuestions": [{"question": "...", "why": "..."}]}

            规则：
            1. 子问题最多 %d 条。单一事实问题（问一个数、一个型号、一条规则）只给 1 条：把原问题改写成更适合检索的说法即可。
            2. 只有问题确实包含多个独立部分时才拆成多条，例如"一共分几档、最长一档做什么"、
               "两边各自的规格和更换周期"、"哪些报警需要停机"。
            3. 每条子问题必须能单独拿去检索，不要写成"再用上一条的结果去查"这类依赖关系。
            4. 型号、编号、版本号、时间范围一律原样保留，不要改写。
            5. needClarification 只在"连检索词都写不出来"时才设为 true（例如用户只说"那个保养周期"、
               完全没给型号）。系统会先用你给出的子问题查一轮，查不到证据才会向用户反问；所以只要你能
               写出可检索的子问题，就把它填上，并同时把 clarifyQuestion 写成一句向用户追问的话。
            6. 不要输出与检索无关的建议，也不要评价问题本身。
            """;

    private final LanguageModel languageModel;

    public QuestionDecomposer(LanguageModel languageModel) {
        this.languageModel = languageModel;
    }

    /**
     * 拆解。**任何解析问题都退化为"单子问题 = 原问题"**，只有模型接口本身不可用
     * （{@link IOException}）才往外冒——那种情况下后面的生成也一定会失败，早报早清楚。
     */
    public DeepPlan.Plan decompose(String question, int maxSubQuestions, DeepLedger ledger)
            throws IOException, InterruptedException {
        String system = SYSTEM.formatted(maxSubQuestions);
        LanguageModel.Completion completion = languageModel.complete(system, "【用户问题】\n" + question);
        ledger.chargeTokens(completion.promptTokens(), completion.completionTokens());
        ledger.noteModel(completion.model());
        DeepPlan.Plan plan = DeepPlan.parse(completion.content(), question, maxSubQuestions);
        log.debug("拆解 question=\"{}\" 子问题={} 需澄清={}", question, plan.subQuestions().size(),
                plan.needClarification());
        return plan;
    }
}
