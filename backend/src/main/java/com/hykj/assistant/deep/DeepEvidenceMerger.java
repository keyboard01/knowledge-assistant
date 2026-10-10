package com.hykj.assistant.deep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hykj.assistant.retrieval.Evidence;

/**
 * 把多轮工具调用带回来的证据合成"一份能交给模型的证据表"。
 *
 * <p>纯函数：不碰数据库、不碰 Spring。合并口径错了会静默地漏证据（§14 那种"表行没进提示词"），
 * 靠人眼看日志是发现不了的。</p>
 *
 * <p>两件事必须一起做，缺一个都会出问题：</p>
 * <ul>
 *   <li><b>去重</b>：同一个片段可能被两次检索、一次读取都带回来。重复片段不只是浪费 token，
 *       它会让模型以为"这条被强调了"，从而给它更高的权重。</li>
 *   <li><b>重编全局序号</b>：每轮检索的证据编号都是从 1 开始的，直接拼起来会出现两套 [1]。
 *       引用校验按"编号 → 证据"翻译，编号一撞，点开引用就会跳到另一份文档。</li>
 * </ul>
 */
public final class DeepEvidenceMerger {

    /**
     * 一份文档最多保留几个片段。
     *
     * <p>16 的量级是"一轮检索最多 6 条 + 两次读取最多 8 条"：§14 根因 1 要修的正是"表行排在
     * 第 7 名之后"，上限压低了就等于白读。真正把篇幅压住的是分步作答（每段只带自己的切片），
     * 不是丢证据——这也是 §14 明确留给我们的一句话：别再用丢证据的办法解决长度问题。</p>
     */
    static final int MAX_FRAGMENTS_PER_DOCUMENT = 16;

    /** 一轮工具调用的产出，带上"这是为哪个子问题查的"。 */
    public record Round(String subQuestionId, List<Evidence> evidences) {

        public Round {
            evidences = List.copyOf(evidences);
        }
    }

    /**
     * @param evidences         全局证据表（编号 1..N 连续）
     * @param slicesBySubQuestion 子问题 -> 它这一路摸到的证据切片。<b>切片里的编号是全局编号</b>，
     *                            所以分步作答时模型写 [5] 与整表里的 [5] 是同一份文档。
     */
    public record Merged(List<Evidence> evidences, Map<String, List<Evidence>> slicesBySubQuestion) {

        public Merged {
            evidences = List.copyOf(evidences);
            Map<String, List<Evidence>> copy = new LinkedHashMap<>();
            slicesBySubQuestion.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            slicesBySubQuestion = Map.copyOf(copy);
        }

        public boolean covered(String subQuestionId) {
            return !slicesBySubQuestion.getOrDefault(subQuestionId, List.of()).isEmpty();
        }
    }

    private DeepEvidenceMerger() {
    }

    public static Merged merge(List<Round> rounds) {
        Map<Long, Doc> documents = new LinkedHashMap<>();
        Map<String, Set<String>> keysBySubQuestion = new LinkedHashMap<>();
        for (Round round : rounds) {
            Set<String> keys = keysBySubQuestion.computeIfAbsent(round.subQuestionId(), key -> new LinkedHashSet<>());
            for (Evidence evidence : round.evidences()) {
                Doc document = documents.computeIfAbsent(evidence.documentId(), id -> new Doc(evidence));
                for (Evidence.Fragment fragment : evidence.fragments()) {
                    String key = key(fragment);
                    keys.add(key);
                    document.fragments.putIfAbsent(key, fragment);
                }
            }
        }

        List<Doc> ordered = new ArrayList<>(documents.values());
        // 排序先按最强片段的分数：这是"哪份文档最相关"的唯一依据。分数一样时按编号，保证结果稳定可断言。
        ordered.sort(Comparator.comparingDouble(Doc::bestScore).reversed()
                .thenComparing(doc -> doc.docNo == null ? "" : doc.docNo));
        List<Evidence> evidences = new ArrayList<>();
        int rank = 1;
        for (Doc doc : ordered) {
            List<Evidence.Fragment> fragments = doc.topFragments();
            if (fragments.isEmpty()) {
                continue;
            }
            evidences.add(new Evidence(rank++, doc.documentId, doc.docNo, doc.title, doc.version, doc.docStatus,
                    doc.secrecy, doc.ownerDept, doc.kernelDocName,
                    Math.max(doc.sourceBestScore, fragments.get(0).score()), fragments));
        }

        Map<String, List<Evidence>> slices = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : keysBySubQuestion.entrySet()) {
            List<Evidence> slice = new ArrayList<>();
            for (Evidence evidence : evidences) {
                List<Evidence.Fragment> mine = evidence.fragments().stream()
                        .filter(fragment -> entry.getValue().contains(key(fragment)))
                        .toList();
                if (!mine.isEmpty()) {
                    slice.add(new Evidence(evidence.rank(), evidence.documentId(), evidence.docNo(), evidence.title(),
                            evidence.version(), evidence.docStatus(), evidence.secrecy(), evidence.ownerDept(),
                            evidence.kernelDocName(), mine.get(0).score(), mine));
                }
            }
            slices.put(entry.getKey(), slice);
        }
        return new Merged(evidences, slices);
    }

    /** 片段自然键：有序号用序号（重解析之后仍然稳定），没有才退回 chunkId。 */
    static String key(Evidence.Fragment fragment) {
        return fragment.chunkIndex() != null ? "i" + fragment.chunkIndex() : "c" + fragment.chunkId();
    }

    private static final class Doc {

        private final long documentId;
        private final String docNo;
        private final String title;
        private final String version;
        private final String docStatus;
        private final String secrecy;
        private final String ownerDept;
        private final String kernelDocName;
        private final double sourceBestScore;
        private final Map<String, Evidence.Fragment> fragments = new LinkedHashMap<>();

        private Doc(Evidence source) {
            this.documentId = source.documentId();
            this.docNo = source.docNo();
            this.title = source.title();
            this.version = source.version();
            this.docStatus = source.docStatus();
            this.secrecy = source.secrecy();
            this.ownerDept = source.ownerDept();
            this.kernelDocName = source.kernelDocName();
            this.sourceBestScore = source.bestScore();
        }

        private List<Evidence.Fragment> topFragments() {
            return fragments.values().stream()
                    .sorted(Comparator.comparingDouble(Evidence.Fragment::score).reversed()
                            .thenComparing(fragment -> fragment.chunkIndex() == null ? Integer.MAX_VALUE
                                    : fragment.chunkIndex()))
                    .limit(MAX_FRAGMENTS_PER_DOCUMENT)
                    .toList();
        }

        private double bestScore() {
            return Math.max(sourceBestScore,
                    fragments.values().stream().mapToDouble(Evidence.Fragment::score).max().orElse(0d));
        }
    }
}
