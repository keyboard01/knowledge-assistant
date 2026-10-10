package com.hykj.assistant.generation;

import java.util.List;

import com.hykj.assistant.retrieval.Evidence;

/**
 * 提示词组装。纯函数：同一份证据必须产出同一段提示词，否则"答错了"到底是检索的错还是提示词的错，
 * 根本无从判断。
 *
 * <p>规则写在系统提示里而不是塞在问题后面，是因为系统提示是模型优先级最高的指令位置；
 * 引用格式 [n] 与证据列表里的编号一一对应，这是 {@link CitationCheck} 能核对的前提。</p>
 */
public final class EvidencePrompt {

    private static final String LINE = "\n";

    /**
     * 系统提示（模型优先级最高的指令位置）。
     *
     * <p>第 6 条的"直接给结论"是 T11 跑分实测出来的：模型有概率先自己"想"一遍再答，
     * 把 max_tokens 用完被截断——截断按拒答处理，用户看到的是"答不了"而不是答案。
     * 所以它不是风格偏好，是功能性约束。</p>
     *
     * <p>T17 又补了一刀"200 字以内"：P2 的分块更细，一份文档能命中十几个片段，
     * 模型于是逐段复述，实测 Q01/Q09/Q12/Q13 全部把 1200 的输出配额写满后被判截断拒答。
     * 篇幅约束比"少给证据"安全——证据少一条是静默漏答，话多一句只是啰嗦。</p>
     */
    public static final String SYSTEM = """
            你是企业内部知识助手。你只能依据下方【证据】回答，不允许使用任何外部知识。

            必须遵守：
            1. 只使用证据中出现的信息。证据里没有的，直接说"知识库中没有相关内容，无法回答"，
               不要用常识补充，不要猜测，不要把相似话题当成答案。
            2. 每一个事实性结论后面都要紧跟引用编号，写成 [1]；一句话综合多条证据时写成 [1][3]。
            3. 只能引用证据里出现过的编号（1 到 N），不得编造编号，不得引用未列出的文档。
            4. 同一编号存在多个版本且结论不同时，必须并列说明各自版本与差异，不要替用户选择。
            5. 证据只能部分支撑问题时，先说能确定的部分，再明确说明哪些部分证据不足。
            6. 用简体中文回答，简明直接：直接给结论，不要输出推理过程，不要复述证据原文，
               不要输出与问题无关的寒暄，也不要复述这些规则。答案控制在 200 字以内
               （同一编号的多个版本必须并列说明时可以放宽到 300 字）。
            """;

    private EvidencePrompt() {
    }

    public static String user(String question, List<Evidence> evidences) {
        StringBuilder prompt = new StringBuilder("【证据】");
        for (Evidence evidence : evidences) {
            prompt.append(LINE).append('[').append(evidence.rank()).append("] 《")
                    .append(evidence.title()).append('》')
                    .append("（编号 ").append(evidence.docNo())
                    .append("，版本 ").append(evidence.version())
                    .append("，").append(evidence.docStatus())
                    .append("，责任部门 ").append(evidence.ownerDept()).append("）");
            for (Evidence.Fragment fragment : evidence.fragments()) {
                prompt.append(LINE).append("    片段").append(location(fragment)).append("：")
                        .append(clean(fragment.text()));
            }
        }
        prompt.append(LINE).append(LINE).append("【问题】").append(LINE).append(clean(question));
        return prompt.toString();
    }

    /**
     * 片段的位置标注。页码来自我们的解析结果（PDF 才有的语义），取不到就什么都不写——
     * 给模型一个编造的页码，比不给页码糟得多，它会当成事实写进答案。
     */
    private static String location(Evidence.Fragment fragment) {
        Integer from = fragment.pageFrom();
        if (from == null) {
            return "";
        }
        Integer to = fragment.pageTo();
        if (to == null || to.equals(from)) {
            return "（第 " + from + " 页）";
        }
        return "（第 " + from + "-" + to + " 页）";
    }

    /**
     * 换行在提示词里是结构（用来分隔证据与问题），不能让片段正文自带的换行把结构撑散。
     * 顺带把连续空白折叠成一个空格：内核切分留下的缩进、以及 CRLF 被拆成两个空格的情况，
     * 都会白白吃掉 token；代价是原文里的对齐排版不再保留——提示词不是给人看的，这个代价可以接受。
     */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").strip();
    }
}
