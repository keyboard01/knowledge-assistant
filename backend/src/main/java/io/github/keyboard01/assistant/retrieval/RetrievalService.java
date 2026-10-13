package io.github.keyboard01.assistant.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.domain.DomainRepository;
import io.github.keyboard01.assistant.domain.DomainService;
import io.github.keyboard01.assistant.domain.KnowledgeDomain;
import io.github.keyboard01.assistant.ingest.DocumentRepository;
import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.AclRule;
import io.github.keyboard01.assistant.security.DocumentAclRepository;

/**
 * 检索编排：召回 → 反查本地登记 → 权限过滤 → 聚合成证据 → 候选不足提示。
 *
 * <p>不做生成——只负责把"应该被看到的证据"摆到桌面上，并留下可解释的账本。</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 过滤前多要一些：权限与反查都会丢命中，按 topN 要多少就只要多少，很容易"看着像没结果"。 */
    private static final int OVERFETCH_FACTOR = 3;
    private static final int MAX_KERNEL_TOPN = 20;

    public static final int DEFAULT_TOP_N = 5;
    public static final int MAX_TOP_N = 10;
    /**
     * 召回下限（**只作用于向量那一路**）。与内核工作区默认的 similarityThreshold 对齐，
     * 避免两处阈值不一致导致"系统说没找到、内核明明有"。
     *
     * <p>关键词通道不走这个数：trigram 相似度与余弦不是一个量纲（实测一条精确命中也就 0.52），
     * 把向量的下限套上去等于直接关掉关键词通道。它有自己的下限，见
     * {@link io.github.keyboard01.assistant.retrieval.LocalChunkRetriever#KEYWORD_MIN_SCORE}。</p>
     */
    public static final double DEFAULT_MIN_SCORE = 0.25;
    /**
     * 作答下限。
     *
     * <p>P1 的 0.45 是**粗粒度分块**下标定的；P2 把块切细之后整个分布上移了
     * （短块之间的余弦天然更高），沿用旧值会让库外问题也越过线。T17 第一轮实测：
     * 应作答 20 题的最低分 0.529（Q03），库里没有 5 题的最高分 0.497（Q22），
     * 中间只有 0.032 的空隙，取中点 0.51。**不是为了让题集好看而下调**——恰恰相反，是往上抬。</p>
     *
     * <p>空隙很窄，所以这一条不能只靠阈值：关键词通道是第二条路
     * （精确词命中 ≥ {@link RrfFusion#STRONG_KEYWORD_SIMILARITY} 也算证据），
     * 分块粒度是第三条（定义句独立成块才有 0.53，埋回整页就只剩 0.21）。</p>
     */
    public static final double ANSWER_MIN_SCORE = 0.51;
    /**
     * 关键词作答下限（P2）：两路任一达标即可作答——向量强说明语义对上了，关键词强说明精确词对上了。
     * 起点取 {@link RrfFusion#ANSWER_KEYWORD_SIMILARITY}（实测：应拒答的最高 0.353，真靠它救场的 0.522），
     * T17 用 30 题集校准后改这里一个数就够。
     */
    public static final double KEYWORD_MIN_SCORE = RrfFusion.ANSWER_KEYWORD_SIMILARITY;

    private final KnowledgeRetriever retriever;
    private final DocumentRepository documents;
    private final DocumentAclRepository aclRepository;
    private final DomainRepository domains;
    private final DomainService domainService;

    public RetrievalService(KnowledgeRetriever retriever, DocumentRepository documents,
                            DocumentAclRepository aclRepository, DomainRepository domains,
                            DomainService domainService) {
        this.retriever = retriever;
        this.documents = documents;
        this.aclRepository = aclRepository;
        this.domains = domains;
        this.domainService = domainService;
    }

    public RetrievalResult search(AccessSubject subject, String domainCode, String question, Integer topN,
                                 Double minScore) throws java.io.IOException, InterruptedException {
        String code = require(domainCode, "请选择知识域");
        String asked = require(question, "问题不能为空");
        int want = clamp(topN == null ? DEFAULT_TOP_N : topN, 1, MAX_TOP_N);
        double floor = clamp(minScore == null ? DEFAULT_MIN_SCORE : minScore, 0d, 1d);
        int kernelTopN = Math.min(Math.max(want * OVERFETCH_FACTOR, want + 4), MAX_KERNEL_TOPN);

        // 先过域级门（fail-closed，域不存在与无权访问同一种拒绝），再判域是否存在/启用。
        domainService.requireAccessible(subject, code);
        KnowledgeDomain domain = domains.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        if (!domain.enabled()) {
            throw new IllegalArgumentException("知识域已停用：" + domain.name());
        }

        AccessFilter filter = AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS);
        long startedAt = System.nanoTime();
        KnowledgeRetriever.Outcome retrieved = retriever.retrieve(
                new KnowledgeRetriever.Query(code, filter, asked, kernelTopN, floor));
        List<RetrievedPassage> hits = retrieved.hits();
        Map<Long, DocumentRef> known = loadDocuments(hits);
        Map<Long, List<AclRule>> acl = aclRepository.findByDocumentIds(known.values().stream()
                .map(DocumentRef::id).distinct().toList());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        RetrievalResult result = EvidenceAssembler.assemble(hits, known, acl, filter,
                new EvidenceAssembler.Context(floor, ANSWER_MIN_SCORE, KEYWORD_MIN_SCORE, kernelTopN, want,
                        elapsedMs, retrieved.blockedHitCount()));

        RetrievalDiagnostics d = result.diagnostics();
        log.info("检索 user={} domain={} topN={} minScore={}/{}(关键词{}) 候选={} 两路[向量={} 关键词={}] 证据={} "
                        + "过滤[无权限={} 未登记={} 未索引={} 重复={}] 最高分={}/关键词{} 重排[{} 最高{}] "
                        + "证据不足={} 版本冲突={} 耗时={}ms 可见范围[{}] question=\"{}\"",
                subject.username(), code, want, floor, ANSWER_MIN_SCORE, KEYWORD_MIN_SCORE, d.kernelHits(),
                d.vectorHits(), d.keywordHits(), d.evidenceCount(),
                d.droppedUnauthorized(), d.droppedUnknown(), d.droppedNotIndexed(), d.droppedDuplicate(),
                String.format("%.4f", d.bestScore()), String.format("%.4f", d.bestKeywordScore()),
                d.rerankApplied() ? "开" : "关", String.format("%.4f", d.bestRerankScore()),
                result.insufficient(), d.versionConflict(), d.elapsedMs(), d.accessFilter(), asked);
        if (d.droppedUnauthorized() > 0) {
            log.info("检索命中里有 {} 条因权限被过滤（user={} domain={}）", d.droppedUnauthorized(),
                    subject.username(), code);
        }
        return result;
    }

    /** 命中片段带着 {@code document_id}，这一步就是"片段 → 本地文档"的唯一入口。 */
    private Map<Long, DocumentRef> loadDocuments(List<RetrievedPassage> hits) {
        List<Long> ids = hits.stream().map(RetrievedPassage::documentId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocumentRef> byId = new LinkedHashMap<>();
        for (DocumentRepository.Row row : documents.findByIds(ids)) {
            byId.put(row.id(), new DocumentRef(row.id(), row.docNo(), row.title(), row.version(),
                    row.docStatus(), row.secrecy(), row.ownerDept(), row.chunkStatus()));
        }
        return byId;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
