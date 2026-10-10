package com.hykj.assistant.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalResult;

/**
 * 把"检索到的证据 + 模型输出"变成"可以给用户看的答案"。纯函数。
 *
 * <p>这里是整条问答链路唯一能拦住错误答案的闸门，所以判定顺序是刻意的：先看证据够不够
 * （不够就根本不调模型），再看模型输出是否完整，最后核对引用。任何一条不满足都拒答，
 * 并把原因码记进账本——拒答率与拒答原因必须能被统计，否则 T11 无从校准。</p>
 */
public final class AnswerComposer {

    public static final String RULE_INSUFFICIENT = "INSUFFICIENT_EVIDENCE";
    public static final String RULE_TRUNCATED = "OUTPUT_TRUNCATED";
    public static final String RULE_EMPTY_OUTPUT = "EMPTY_MODEL_OUTPUT";
    public static final String RULE_OUT_OF_RANGE = "CITATION_OUT_OF_RANGE";
    public static final String RULE_NO_CITATION = "NO_CITATION";

    /** {@code truncated} 来自模型返回的 finish_reason，只有适配层知道怎么读它。 */
    public record Meta(String model, boolean modelCalled, boolean truncated, int promptTokens,
                       int completionTokens, long elapsedMs) {
    }

    private AnswerComposer() {
    }

    public static GeneratedAnswer compose(RetrievalResult retrieval, String modelText, Meta meta) {
        List<Evidence> evidences = retrieval.evidences();
        int evidenceCount = evidences.size();

        // 1) 检索阶段已判定证据不足：直接拒答，不调模型。既省一次 token，也让"答不上来"与模型无关
        //    ——换任何一个模型，这里的结果都一样，把变量按住才谈得上排查。
        if (retrieval.insufficient()) {
            return refuse(RULE_INSUFFICIENT, unableText(retrieval), evidenceCount, List.of(), meta, evidences);
        }
        // 2) 被长度限制截断：半句话最危险，它可能刚好丢掉"版本不同"这类限定语
        if (meta.truncated()) {
            return refuse(RULE_TRUNCATED,
                    "生成内容被长度限制截断，无法保证结论完整，请缩小问题范围后重试。",
                    evidenceCount, List.of(), meta, evidences);
        }
        // 3) 模型什么都没返回
        if (modelText == null || modelText.isBlank()) {
            return refuse(RULE_EMPTY_OUTPUT, "生成模型没有返回内容，请稍后重试。", evidenceCount, List.of(), meta,
                    evidences);
        }

        CitationCheck.Result check = CitationCheck.parse(modelText, evidenceCount);
        // 4) 引用越界 = 编造来源。答案读起来可能毫无破绽，但出处对不上就一律不给看。
        if (check.hasOutOfRange()) {
            return refuse(RULE_OUT_OF_RANGE, outOfRangeText(check.outOfRange()), evidenceCount,
                    check.outOfRange(), meta, evidences);
        }
        // 5) 一条引用都没有：多半是模型自己判断答不了，那就保留它的原话（比服务端硬写一句更像人话）；
        //    也可能是它忘了标引用——同样按无法回答处理，没有出处的内容不接受。
        if (!check.hasCitation()) {
            return refuse(RULE_NO_CITATION, modelText.strip(), evidenceCount, List.of(), meta, evidences);
        }
        return new GeneratedAnswer(modelText.strip(), citations(check.cited(), evidences), false, null,
                retrieval.accessNotice(), List.copyOf(evidences),
                diagnostics(meta, null, evidenceCount, check.cited().size(), List.of()));
    }

    private static GeneratedAnswer refuse(String rule, String text, int evidenceCount, List<Integer> outOfRange,
                                          Meta meta, List<Evidence> evidences) {
        // 拒答时提示已经并进 text（见 unableText），notice 保持 null，否则同一句话会出现两遍
        return new GeneratedAnswer(text, List.of(), true, text, null, List.copyOf(evidences),
                diagnostics(meta, rule, evidenceCount, 0, outOfRange));
    }

    /** 引用只做"翻译"：把编号换成可定位的来源。序号的合法性已由 {@link CitationCheck} 保证。 */
    private static List<GeneratedAnswer.Citation> citations(List<Integer> ranks, List<Evidence> evidences) {
        Map<Integer, Evidence> byRank = new HashMap<>();
        for (Evidence evidence : evidences) {
            byRank.put(evidence.rank(), evidence);
        }
        List<GeneratedAnswer.Citation> out = new ArrayList<>();
        for (int rank : ranks) {
            Evidence evidence = byRank.get(rank);
            if (evidence == null) {
                continue;
            }
            // 引用指的是这条证据里最强的那个片段，页码也就取它的：指向同一个片段，
            // 否则会出现"引用跳到第 7 页的片段，标签却写着第 2 页"。
            Evidence.Fragment best = evidence.fragments().isEmpty() ? null : evidence.fragments().get(0);
            String chunkId = best == null ? null : best.chunkId();
            out.add(new GeneratedAnswer.Citation(rank, evidence.documentId(), evidence.docNo(),
                    evidence.title(), evidence.version(), chunkId,
                    best == null ? null : best.pageFrom(), best == null ? null : best.pageTo()));
        }
        return List.copyOf(out);
    }

    private static GenerationDiagnostics diagnostics(Meta meta, String rule, int evidenceCount, int citedCount,
                                                     List<Integer> outOfRange) {
        return new GenerationDiagnostics(meta.model(), meta.modelCalled(), evidenceCount, citedCount,
                List.copyOf(outOfRange), meta.promptTokens(), meta.completionTokens(), rule, meta.truncated(),
                meta.elapsedMs());
    }

    /**
     * 拒答文案 = 一句结论 + 权限提示（若有）+ 检索侧原因（若有）。
     * 权限那段排在前面对应 T07 的判断：它改变的是"该找管理员"，而不是"该换关键词"。
     */
    private static String unableText(RetrievalResult retrieval) {
        StringBuilder text = new StringBuilder("根据知识库中现有的内容无法可靠回答这个问题。");
        if (retrieval.accessNotice() != null) {
            text.append(' ').append(retrieval.accessNotice()).append('。');
        }
        String hint = retrieval.hint();
        if (hint != null && !hint.isBlank()) {
            text.append(' ').append(hint.strip());
        }
        return text.toString();
    }

    private static String outOfRangeText(List<Integer> outOfRange) {
        return "生成结果引用了本次检索中不存在的证据编号 " + outOfRange
                + "，属于编造来源，已拒绝返回该答案，请换个问法或补充关键词后重试。";
    }
}
