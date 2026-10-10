package com.hykj.assistant.deep;

import java.util.List;

import com.hykj.assistant.generation.EvidencePrompt;
import com.hykj.assistant.retrieval.Evidence;

/**
 * 深度通道的提示词。
 *
 * <p>与快速模式（{@link EvidencePrompt#SYSTEM}）是两份，不是一份：快速模式那份写着"答案控制在 200 字以内"，
 * 那条约束是为"一次生成整段答案"服务的；深度通道把长答案拆成了多段，每段只需要管好自己这一部分，
 * 于是单段的篇幅从 200 字放宽到 400 字——**放宽的前提是段数变多，而不是放松要求**。</p>
 *
 * <p>第 4 条（表格类证据要逐行读全）是 §14 根因 1 的正面要求：模型看到表头就下结论，是那次实验里
 * 最典型的塌法；现在证据补齐了，还得明确要求它别只看表头。</p>
 */
public final class DeepAnswerPrompt {

    public static final String SYSTEM = """
            你是企业内部知识助手的深度分析模块。你只能依据下方【证据】回答**当前这一个子问题**，
            不允许使用任何外部知识。

            必须遵守：
            1. 只使用证据里出现的信息。证据里没有的，直接说"本段证据不足，无法确定"，不要用常识补充，不要猜。
            2. 每一个事实性结论后面紧跟引用编号，写成 [1]；综合多条时写成 [1][3]。
               只能引用证据里出现过的编号，不得编造编号。
            3. 同一编号存在多个版本且结论不同时，必须并列说明各自版本与差异，不要替用户选择。
            4. 表格类证据要**逐行读全**再下结论：只看到表头、没看到数据行时，
               明确说明"只检索到表头，数据行未见"，不要按经验补全。
            5. 只回答当前子问题，不要替其他子问题作答，不要复述证据原文，不要输出推理过程。
            6. 用简体中文回答，结论先行，控制在 400 字以内。
            """;

    private DeepAnswerPrompt() {
    }

    /**
     * 组装一段的用户提示词。
     *
     * <p>直接复用 {@link EvidencePrompt#user} 而不是另写一份：证据块的排版（编号、标题、版本、页码、片段）
     * 是一套约定，两处各排一遍迟早会走形——而"编号与证据一一对应"正是引用校验能成立的前提。</p>
     */
    public static String user(String subQuestion, List<Evidence> evidences) {
        return EvidencePrompt.user(subQuestion, evidences);
    }
}
