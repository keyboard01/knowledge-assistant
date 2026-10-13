package io.github.keyboard01.assistant.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AclRule;

/**
 * 把检索命中变成可引用、已过权限的证据。
 *
 * <p>纯函数：只吃内存里的命中与登记表投影，不碰数据库、不碰 Spring。权限过滤与聚合的正确性
 * 靠单测钉住——这段逻辑一旦漏过滤，泄漏是静默的，靠人眼看日志发现不了。</p>
 */
public final class EvidenceAssembler {

    /**
     * @param minScore        向量召回下限（召回要宽）
     * @param answerMinScore  向量作答下限：最强片段的余弦相似度低于它，就不能只靠向量作答
     * @param keywordMinScore 关键词作答下限：精确词命中到这条线以上也算证据（两路任一达标即可作答）
     * @param blockedHitCount 检索侧在 SQL 里挡下的命中条数（自建通道才有；内核通道给 0，
     *                        它不在库里过滤，由这里自己数）
     */
    public record Context(double minScore, double answerMinScore, double keywordMinScore, int kernelTopN,
                          int requestedTopN, long elapsedMs, int blockedHitCount) {
    }

    /**
     * 一份证据最多保留几个片段。
     *
     * <p>实测（T17 第一轮）：细粒度分块之后，同一份文档能有十几个片段同时命中，
     * 它们全部进了提示词，模型就会一段一段地复述——输出撞上 max_tokens 被截断，
     * 按规则判成拒答（Q01/Q09/Q12/Q13 都是这么栽的）。片段是按分数排过序的，
     * 但砍得太狠同样出事：Q19「几小时换油气分离器」的答案行排在文档第 5 个片段上，
     * 上限设 3 时它进不了提示词，模型只能漏答。6 是"够覆盖答案行、又不至于把模型逼成复述"的取值；
     * 真正的截断问题在提示词的篇幅约束上解决（见 {@code EvidencePrompt.SYSTEM} 第 6 条），不靠丢证据。</p>
     */
    static final int MAX_FRAGMENTS_PER_EVIDENCE = 6;

    private EvidenceAssembler() {
    }

    public static RetrievalResult assemble(List<RetrievedPassage> hits,
                                           Map<Long, DocumentRef> documentsById,
                                           Map<Long, List<AclRule>> aclByDocumentId,
                                           AccessFilter filter,
                                           Context context) {
        List<RetrievedPassage> safeHits = hits == null ? List.of() : hits;
        int droppedUnauthorized = 0;
        int droppedUnknown = 0;
        int droppedNotIndexed = 0;
        int droppedDuplicate = 0;
        Set<String> seenChunks = new HashSet<>();
        Map<Long, DocumentRef> documents = new LinkedHashMap<>();
        Map<Long, List<RetrievedPassage>> fragmentsByDocument = new LinkedHashMap<>();

        for (RetrievedPassage hit : safeHits) {
            DocumentRef document = documentsById.get(hit.documentId());
            if (document == null) {
                droppedUnknown++;
                continue;
            }
            // 先判权限再判索引状态：看不见的文档，连"它为什么没出现"都不该暴露出来
            if (!filter.allows(aclByDocumentId.getOrDefault(document.id(), List.of()))) {
                droppedUnauthorized++;
                continue;
            }
            if (!document.searchable()) {
                droppedNotIndexed++;
                continue;
            }
            if (!seenChunks.add(hit.chunkId())) {
                droppedDuplicate++;
                continue;
            }
            documents.put(document.id(), document);
            fragmentsByDocument.computeIfAbsent(document.id(), key -> new ArrayList<>()).add(hit);
        }

        List<RankedEvidence> ranked = new ArrayList<>();
        for (Map.Entry<Long, List<RetrievedPassage>> entry : fragmentsByDocument.entrySet()) {
            ranked.add(new RankedEvidence(toEvidence(documents.get(entry.getKey()), entry.getValue()),
                    sortKey(entry.getValue())));
        }
        // 有重排分就按重排分排（重排正是为了纠正 RRF 的名次），没有就回落向量最高分——逐位退化为引入重排前的顺序。
        ranked.sort(Comparator.comparingDouble(RankedEvidence::sortKey).reversed()
                .thenComparing(item -> item.evidence().docNo()));

        List<Evidence> evidences = new ArrayList<>();
        for (int index = 0; index < ranked.size() && index < context.requestedTopN(); index++) {
            Evidence evidence = ranked.get(index).evidence();
            evidences.add(new Evidence(index + 1, evidence.documentId(), evidence.docNo(), evidence.title(),
                    evidence.version(), evidence.docStatus(), evidence.secrecy(), evidence.ownerDept(),
                    evidence.bestScore(), evidence.fragments()));
        }

        // 自建通道在 SQL 里就挡掉了，所以 Java 侧数到 0。这个计数器留给"两路都要数"的场景。
        droppedUnauthorized += context.blockedHitCount();
        int kernelHits = safeHits.size();
        int vectorHits = (int) safeHits.stream().filter(hit -> hit.vectorRank() > 0).count();
        int keywordHits = (int) safeHits.stream().filter(hit -> hit.keywordRank() > 0).count();
        // bestScore 取"所有候选文档里最强的余弦"，与排序无关——重排会换掉 evidences.get(0)，
        // 若还从第一名取分，重排就间接挪动了作答阈值。取全局最大则无论排在前面的换成谁，
        // 阈值判据都还是那条余弦线（不重排时它与旧实现等价：按分降序的第一名本就是最大）。
        double bestScore = ranked.stream().mapToDouble(item -> item.evidence().bestScore()).max().orElse(0d);
        double bestKeywordScore = safeHits.stream().mapToDouble(RetrievedPassage::keywordScore).max().orElse(0d);
        // 重排只入账、不做判据：bestRerankScore 给"重排到底把分数抬了还是压了"留证据，供阈值重标定用。
        boolean rerankApplied = safeHits.stream().anyMatch(RetrievedPassage::hasRerankScore);
        double bestRerankScore = safeHits.stream().filter(RetrievedPassage::hasRerankScore)
                .mapToDouble(RetrievedPassage::rerankScore).max().orElse(0d);
        // 两路任一够强就算有证据：向量强说明语义对上了，关键词强说明精确词对上了。
        // 两路都弱才是真的"不知道"——P1 只有向量一路，所以那时只能靠单一阈值，也才会出现
        // "短问题撞长块"那种漏答（阈值再调都是换一种错法）。
        boolean tooWeak = !evidences.isEmpty()
                && bestScore < context.answerMinScore()
                && bestKeywordScore < context.keywordMinScore();
        boolean insufficient = evidences.isEmpty() || tooWeak;
        // "全被权限挡下"要同时给 hint 与 accessNotice 用：前者解释原因，后者据此决定不重复说一遍。
        // 自建通道下 kernelHits 可以是 0（命中全在库里就被挡掉了），所以判据不是"相等"而是"不少于"：
        // 挡下的比带回来的还多，只可能是"你能看到的都排在被挡的后面"。
        boolean allBlockedByPermission = insufficient && droppedUnauthorized > 0
                && droppedUnauthorized >= kernelHits;
        return new RetrievalResult(List.copyOf(evidences), insufficient,
                hint(insufficient, kernelHits, droppedUnknown, droppedNotIndexed, evidences.size(),
                        context.requestedTopN(), bestScore, context.answerMinScore(), bestKeywordScore,
                        context.keywordMinScore(), allBlockedByPermission),
                accessNotice(droppedUnauthorized, allBlockedByPermission),
                new RetrievalDiagnostics(filter.describe(),
                        context.requestedTopN(), context.minScore(), context.answerMinScore(),
                        context.keywordMinScore(), context.kernelTopN(), kernelHits, vectorHits, keywordHits,
                        bestKeywordScore, droppedUnauthorized, droppedUnknown, droppedNotIndexed,
                        droppedDuplicate, evidences.size(), ranked.size(), bestScore,
                        hasVersionConflict(evidences), context.elapsedMs(), rerankApplied, bestRerankScore));
    }

    /** 证据 + 它的文档级排序键（重排分优先、回落向量最高分）。纯为排序服务，不进入对外结构。 */
    private record RankedEvidence(Evidence evidence, double sortKey) {
    }

    /**
     * 文档排序键：该文档命中的片段里最强的重排分；没有任何重排分时回落向量最高分。
     *
     * <p>为什么不直接改 {@code Evidence.bestScore}：那会破坏"bestScore 始终是余弦"的口径，
     * 并牵动 {@code deep} 通道用 bestScore 选的锚点。重排只该改"谁排前面"，不该改"这份文档有多相关"
     * 的数值含义——阈值判定（{@code insufficient}）继续读余弦。</p>
     */
    private static double sortKey(List<RetrievedPassage> passages) {
        double key = Double.NEGATIVE_INFINITY;
        for (RetrievedPassage passage : passages) {
            double value = passage.rerankScore() != null ? passage.rerankScore() : passage.score();
            if (value > key) {
                key = value;
            }
        }
        return key == Double.NEGATIVE_INFINITY ? 0d : key;
    }

    private static Evidence toEvidence(DocumentRef document, List<RetrievedPassage> passages) {
        List<Evidence.Fragment> fragments = passages.stream()
                .sorted(Comparator.comparingDouble(RetrievedPassage::score).reversed())
                .limit(MAX_FRAGMENTS_PER_EVIDENCE)
                .map(passage -> new Evidence.Fragment(passage.chunkId(), passage.chunkIndex(), passage.text(),
                        passage.score(), passage.distance(), passage.wordCount(), passage.pageFrom(),
                        passage.pageTo(), passage.sectionPath(), passage.kind(), passage.keywordScore(),
                        passage.vectorRank(), passage.keywordRank()))
                .toList();
        return new Evidence(0, document.id(), document.docNo(), document.title(), document.version(),
                document.docStatus(), document.secrecy(), document.ownerDept(),
                fragments.get(0).score(), fragments);
    }

    /** 同一文档编号出现多个版本：回答必须并列说明，不能悄悄挑一个。 */
    private static boolean hasVersionConflict(List<Evidence> evidences) {
        Map<String, Set<String>> versionsByDocNo = new LinkedHashMap<>();
        for (Evidence evidence : evidences) {
            versionsByDocNo.computeIfAbsent(evidence.docNo(), key -> new TreeSet<>()).add(evidence.version());
        }
        return versionsByDocNo.values().stream().anyMatch(versions -> versions.size() > 1);
    }

    private static String hint(boolean insufficient, int kernelHits, int droppedUnknown, int droppedNotIndexed,
                               int evidenceCount, int requestedTopN, double bestScore, double answerMinScore,
                               double bestKeywordScore, double keywordMinScore, boolean allBlockedByPermission) {
        List<String> notes = new ArrayList<>();
        if (insufficient) {
            // 先判"被权限挡住"：自建通道在 SQL 层就把不可见的挡掉了，kernelHits 会是 0，
            // 若按"零命中"先答，用户会以为库里没有，而实际上他该做的是去找管理员。
            if (allBlockedByPermission) {
                notes.add("命中的内容你都没有访问权限，请联系管理员开通");
            } else if (kernelHits == 0) {
                notes.add("知识库里没有找到相关内容，建议换个说法或补充关键词");
            } else if (evidenceCount > 0) {
                notes.add("检索到的证据相关性偏低（向量最高分 " + score(bestScore) + " 低于 " + score(answerMinScore)
                        + "，关键词最高分 " + score(bestKeywordScore) + " 低于 " + score(keywordMinScore)
                        + "），建议补充更具体的关键词或换个知识域");
            } else if (droppedUnknown > 0) {
                notes.add("命中的片段反查不到本地文档，可能已被删除");
            } else if (droppedNotIndexed > 0) {
                notes.add("命中的文档当前不在可检索状态（索引失败或尚未同步）");
            } else {
                notes.add("证据不足，无法作答");
            }
        } else if (evidenceCount < requestedTopN) {
            notes.add("仅找到 " + evidenceCount + " 份相关文档，少于请求的 " + requestedTopN + " 份");
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /**
     * 权限提示单独成型：它改变的是"该找管理员"而不是"该换关键词"，属于用户必须看到的信息
     * ——少了关键证据的答案，看起来和完整答案一模一样。
     *
     * <p>全被挡下时不重复：那种情况 {@code hint} 里已经有一句更准确的解释。</p>
     */
    static String accessNotice(int droppedUnauthorized, boolean allBlockedByPermission) {
        if (droppedUnauthorized <= 0 || allBlockedByPermission) {
            return null;
        }
        return "另有 " + droppedUnauthorized + " 条更相关的命中你无权访问，请联系管理员开通";
    }

    private static String score(double value) {
        return String.format("%.2f", value);
    }
}
