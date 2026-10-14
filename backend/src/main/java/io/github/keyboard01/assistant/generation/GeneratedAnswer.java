package io.github.keyboard01.assistant.generation;

import java.util.List;

import io.github.keyboard01.assistant.retrieval.Evidence;

/**
 * 一次问答的产出。
 *
 * <p>{@code refused = true} 表示"不给答案"，此时 {@code answer} 是给用户看的解释文案——可能是
 * 服务端写的固定说明，也可能是模型自己的"知识库里没有"原话，但绝不是一个需要被当成结论的答案。
 * 前端只需看这一个布尔量：真就按"无法回答"渲染，假才把 answer 当结论、把 citations 当出处。</p>
 *
 * <p>{@code notice} 是随答案一起给出的提醒（例如"另有 N 条更相关的命中你无权访问"）。它必须被展示：
 * 少了关键证据的答案看起来和完整答案一模一样，用户没有机会察觉自己看到的是残缺版本。
 * 拒答时提示已经并进 {@code answer}，这里恒为 null，避免同一句话出现两遍。</p>
 *
 * <p>{@code evidences} 是本次实际喂给模型的证据（含片段原文），随答案一起返回，前端才能做到
 * "点引用跳原文并高亮"——只给一个 chunkId，前端还得再检索一次才能找到那段话，而且两次结果未必一致。
 * 内容都是本次检索已授权过的，不含额外信息。</p>
 *
 * <p>{@code traceId} 是这次问答在账本（{@code qa_trace}）里的行号。它在 {@code compose} 之后才产生
 * （要先有答案才能记账），所以由 {@code GenerationService} 用 {@link #withTraceId} 回填。
 * 前端的"点赞/点踩"与评测用例都要靠它把评价挂回**具体这一次**问答——只给问题原文，
 * 遇到重复提问就分不清是哪一次了。</p>
 */
public record GeneratedAnswer(String answer, List<Citation> citations, boolean refused, String refusalReason,
                              String notice, List<Evidence> evidences, GenerationDiagnostics diagnostics,
                              Long traceId) {

    /** 记账之后回填 traceId（record 不可变，只能拷贝一份）。 */
    public GeneratedAnswer withTraceId(Long id) {
        return new GeneratedAnswer(answer, citations, refused, refusalReason, notice, evidences, diagnostics, id);
    }

    /**
     * 一条引用：证据序号 + 定位信息。
     * {@code chunkId} 是 T09"点引用跳原文"的锚点；{@code version} 必须带上，因为同一编号可能有多个版本。
     */
    public record Citation(int rank, long documentId, String docNo, String title, String version, String chunkId,
                           Integer pageFrom, Integer pageTo) {

        /**
         * P1 的调用方式：不带页码。
         *
         * <p>页码来自**我们自己的解析结果**，不是模型写的——模型只产出 [n]，
         * 页码由服务端按 n 去查。这样"页码"永远不可能被模型编造出来。</p>
         */
        public Citation(int rank, long documentId, String docNo, String title, String version, String chunkId) {
            this(rank, documentId, docNo, title, version, chunkId, null, null);
        }
    }
}
