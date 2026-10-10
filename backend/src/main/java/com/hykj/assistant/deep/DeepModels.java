package com.hykj.assistant.deep;

import java.util.List;

import com.hykj.assistant.generation.GeneratedAnswer;
import com.hykj.assistant.retrieval.Evidence;

/**
 * 深度通道（S5~S8）的对外形状。
 *
 * <p>字段名刻意与 {@code GeneratedAnswer} 对齐的部分（{@code answer} / {@code citations} /
 * {@code refused} / {@code notice} / {@code evidences} / {@code diagnostics}）不是巧合：前端那套
 * "点引用跳原文"的渲染依赖它们，两套通道共用一份渲染代码比各写一遍更不容易走形。</p>
 */
public final class DeepModels {

    private DeepModels() {
    }

    /** S5 的子问题。{@code why} 是模型给的"这条为什么能回答问题"，只用于展示与排障，不参与判定。 */
    public record SubQuestion(String id, String question, String why) {
    }

    /**
     * 一次工具调用或一次退出判断的留痕。
     *
     * <p>{@code newFragments} 是这一步**新带回来的**片段数：它是 S7 最要紧的判据（本轮没有新增证据
     * 就是一个合法的结束状态），只记"调用了什么"根本看不出该不该退出。</p>
     */
    public record Step(int seq, String action, String detail, String query, Long documentId,
                       Integer fromChunkIndex, int newFragments, long elapsedMs) {
    }

    /**
     * 分步作答的一段：一个子问题 + 它的答案。
     *
     * <p>{@code answered=false} 表示这一段只有"为什么答不了"的说明；{@code refusalRule} 是机器可读的原因码
     * （证据不足 / 输出截断 / 引用越界 / 预算停止）。分开记的原因与整条链路一致：**拒答率与拒答原因必须能统计**，
     * 否则"深度模式到底改善了什么"只能靠感觉。</p>
     */
    public record Section(int index, String question, String text, List<Integer> citedRanks, boolean answered,
                          String refusalRule) {
    }

    /** 深度通道的账本：比快速模式多出"探索花了多少"。 */
    public record DeepDiagnostics(String model, boolean modelCalled, int subQuestionCount, int sectionCount,
                                  int answeredSections, int evidenceCount, int citedCount, int toolCalls,
                                  int rounds, int promptTokens, int completionTokens, long elapsedMs,
                                  String budgetStop, String stopReason) {
    }

    /**
     * 深度问答的产出。
     *
     * <p>{@code stopReason} 是"为什么停在这里"：{@code NO_NEW_EVIDENCE}（没新增证据了）、
     * {@code MODEL_FINISH}（模型说够了）、{@code CANCELLED}、以及各种预算码。它必须进响应——
     * 用户看到"答案有点短"时，得能分清是知识库没有，还是我们自己的预算卡住了。</p>
     */
    public record DeepAnswer(String runId, String domainCode, String question, String mode, String answer,
                             List<Section> sections, List<SubQuestion> plan, List<Step> steps,
                             List<GeneratedAnswer.Citation> citations, boolean refused, String refusalRule,
                             String notice, String clarifyQuestion, String stopReason, String stopMessage,
                             List<Evidence> evidences, DeepDiagnostics diagnostics) {
    }
}
