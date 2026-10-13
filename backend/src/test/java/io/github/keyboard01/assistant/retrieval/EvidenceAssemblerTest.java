package io.github.keyboard01.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.AclRule;

/**
 * 权限过滤与证据聚合的正确性靠这里钉住：这段逻辑一旦漏过滤，泄漏是静默的，看日志发现不了。
 */
class EvidenceAssemblerTest {

    private static final AccessFilter ADMIN = AccessFilter.of(AccessSubject.of("admin", "admin", null), "d");
    private static final AccessFilter DEMO = AccessFilter.of(AccessSubject.of("demo", "viewer", null), "d");

    private static final List<AclRule> PUBLIC = List.of(new AclRule("ALL", "*", "READ"));
    private static final List<AclRule> SECRET = List.of(new AclRule("ROLE", "admin", "READ"));

    @Test
    @DisplayName("同一文档的多个命中聚合成一条证据，片段按分数排序，rank 从 1 开始")
    void mergesFragmentsOfSameDocument() {
        DocumentRef document = doc(3, "QM-ZD-2026-003", "V3.2", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(document.id(), "c-1", 0.58, "第一章"),
                hit(document.id(), "c-2", 0.66, "第二章"));

        RetrievalResult result = EvidenceAssembler.assemble(hits, Map.of(document.id(), document),
                Map.of(document.id(), PUBLIC), ADMIN, context(5));

        assertEquals(1, result.evidences().size());
        Evidence evidence = result.evidences().get(0);
        assertEquals(1, evidence.rank());
        assertEquals(0.66, evidence.bestScore(), 1e-9);
        assertEquals(List.of("c-2", "c-1"), evidence.fragments().stream()
                .map(Evidence.Fragment::chunkId).toList());
        assertFalse(result.insufficient());
        assertEquals(2, result.diagnostics().kernelHits());
    }

    @Test
    @DisplayName("无权限的命中被丢弃：viewer 只拿得到公开文档")
    void dropsUnauthorizedHits() {
        DocumentRef publicDoc = doc(1, "QM-ZD-2026-011", "V1.4", "INDEXED");
        DocumentRef secretDoc = doc(2, "QM-ZD-2026-021", "V0.9", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(secretDoc.id(), "s-1", 0.91, "秘密内容"),
                hit(publicDoc.id(), "p-1", 0.42, "公开内容"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(publicDoc.id(), publicDoc, secretDoc.id(), secretDoc),
                Map.of(publicDoc.id(), PUBLIC, secretDoc.id(), SECRET), DEMO, context(5));

        assertEquals(1, result.evidences().size());
        assertEquals("QM-ZD-2026-011", result.evidences().get(0).docNo());
        assertEquals(1, result.diagnostics().droppedUnauthorized());
    }

    @Test
    @DisplayName("既没权限又没就绪的文档只计入无权限——不暴露它的就绪状态")
    void authorizationIsCheckedBeforeReadiness() {
        DocumentRef secret = doc(9, "QM-ZD-2026-021", "V0.9", "FAILED");

        RetrievalResult result = EvidenceAssembler.assemble(
                List.of(hit(secret.id(), "s-1", 0.9, "秘密")),
                Map.of(secret.id(), secret), Map.of(secret.id(), SECRET), DEMO, context(5));

        assertEquals(1, result.diagnostics().droppedUnauthorized());
        assertEquals(0, result.diagnostics().droppedNotIndexed());
    }

    @Test
    @DisplayName("反查不到、未就绪、重复片段各自计数，都不会变成证据")
    void countsUnknownNotIndexedAndDuplicate() {
        DocumentRef indexed = doc(1, "A-1", "V1.0", "INDEXED");
        DocumentRef pending = doc(2, "A-2", "V1.0", "FAILED");
        List<RetrievedPassage> hits = List.of(
                hit(99, "x-1", 0.90, "反查不到本地文档"),
                hit(pending.id(), "n-1", 0.80, "登记了但片段没建好"),
                hit(indexed.id(), "k-1", 0.70, "正常片段"),
                hit(indexed.id(), "k-1", 0.70, "同一个片段的重复命中"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(indexed.id(), indexed, pending.id(), pending),
                Map.of(indexed.id(), PUBLIC, pending.id(), PUBLIC), ADMIN, context(5));

        RetrievalDiagnostics d = result.diagnostics();
        assertEquals(1, d.droppedUnknown());
        assertEquals(1, d.droppedNotIndexed());
        assertEquals(1, d.droppedDuplicate());
        assertEquals(1, result.evidences().size());
        assertEquals(1, result.evidences().get(0).fragments().size());
    }

    @Test
    @DisplayName("按最高分排序、按 topN 截断，rank 连续；少于请求份数时给候选不足提示")
    void sortsTruncatesAndWarnsAboutShortfall() {
        DocumentRef best = doc(1, "A-1", "V1.0", "INDEXED");
        DocumentRef middle = doc(2, "A-2", "V1.0", "INDEXED");
        DocumentRef last = doc(3, "A-3", "V1.0", "INDEXED");
        Map<Long, DocumentRef> known = Map.of(best.id(), best,
                middle.id(), middle, last.id(), last);
        Map<Long, List<AclRule>> acl = Map.of(best.id(), PUBLIC, middle.id(), PUBLIC, last.id(), PUBLIC);
        List<RetrievedPassage> hits = List.of(
                hit(middle.id(), "m-1", 0.70, "中"),
                hit(last.id(), "l-1", 0.50, "低"),
                hit(best.id(), "b-1", 0.90, "高"));

        RetrievalResult truncated = EvidenceAssembler.assemble(hits, known, acl, ADMIN, context(2));
        assertEquals(List.of(1, 2), truncated.evidences().stream().map(Evidence::rank).toList());
        assertEquals(List.of("A-1", "A-2"), truncated.evidences().stream().map(Evidence::docNo).toList());
        assertNull(truncated.hint(), "正好凑够请求份数时不该提示候选不足");
        assertEquals(2, truncated.diagnostics().evidenceCount());
        assertEquals(3, truncated.diagnostics().documentCount(), "两份是截断的结果，三份才是库里实际有的");

        RetrievalResult shortfall = EvidenceAssembler.assemble(hits, known, acl, ADMIN, context(5));
        assertEquals(3, shortfall.evidences().size());
        assertTrue(shortfall.hint().contains("少于请求的 5 份"), shortfall.hint());
    }

    @Test
    @DisplayName("同一编号的两个版本并列保留，并标记版本冲突（不合并、不挑一个）")
    void keepsBothVersionsAndFlagsConflict() {
        DocumentRef current = doc(3, "QM-ZD-2026-003", "V3.2", "INDEXED");
        DocumentRef obsolete = doc(2, "QM-ZD-2026-003", "V2.8", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(obsolete.id(), "v28-1", 0.61, "旧版"),
                hit(current.id(), "v32-1", 0.66, "现行"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(current.id(), current, obsolete.id(), obsolete),
                Map.of(current.id(), PUBLIC, obsolete.id(), PUBLIC), ADMIN, context(5));

        assertEquals(List.of("V3.2", "V2.8"), result.evidences().stream().map(Evidence::version).toList());
        assertTrue(result.diagnostics().versionConflict());
        assertEquals(2, result.diagnostics().documentCount());
    }

    @Test
    @DisplayName("零命中：标记证据不足并建议换说法")
    void emptyHitsMeansInsufficient() {
        RetrievalResult result = EvidenceAssembler.assemble(List.of(), Map.of(), Map.of(), ADMIN, context(5));

        assertTrue(result.insufficient());
        assertTrue(result.hint().contains("没有找到"), result.hint());
        assertEquals(0d, result.diagnostics().bestScore(), 1e-9);
    }

    @Test
    @DisplayName("命中的全被权限挡住：提示\"没权限\"而不是\"没找到\"")
    void allDroppedByPermissionExplainsWhy() {
        DocumentRef secret = doc(2, "QM-ZD-2026-021", "V0.9", "INDEXED");
        RetrievalResult result = EvidenceAssembler.assemble(
                List.of(hit(secret.id(), "s-1", 0.9, "秘密"), hit(secret.id(), "s-2", 0.8, "秘密")),
                Map.of(secret.id(), secret), Map.of(secret.id(), SECRET), DEMO, context(5));

        assertTrue(result.insufficient());
        assertTrue(result.hint().contains("访问权限"), result.hint());
    }

    @Test
    @DisplayName("有命中但被权限挡下一部分：必须告知\"另有更相关的命中无权访问\"，不能静默降级")
    void warnsAboutUnauthorizedHitsWhenEvidenceRemains() {
        DocumentRef visible = doc(1, "QM-ZD-2026-011", "V1.4", "INDEXED");
        DocumentRef secret = doc(2, "QM-ZD-2026-021", "V0.9", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(secret.id(), "s-1", 0.88, "秘密内容"),
                hit(visible.id(), "p-1", 0.60, "公开内容"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(visible.id(), visible, secret.id(), secret),
                Map.of(visible.id(), PUBLIC, secret.id(), SECRET), DEMO, context(5));

        assertEquals(1, result.evidences().size());
        assertFalse(result.insufficient());
        assertTrue(result.accessNotice().contains("无权访问"), result.accessNotice());
        String hint = result.hint();
        assertTrue(hint == null || !hint.contains("无权访问"),
                "权限提示不能混在排障用的 hint 里，否则'少于请求的 N 份'会跟着漏给用户：" + hint);
        assertEquals(1, result.diagnostics().droppedUnauthorized());
    }

    @Test
    @DisplayName("最高分低于作答下限：证据相关性偏低，标记证据不足而不是硬答")
    void weakBestScoreIsInsufficientEvenWithEvidence() {
        DocumentRef noisy = doc(1, "QM-ZD-2026-021", "V0.9", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(noisy.id(), "n-1", 0.38, "沾边"),
                hit(noisy.id(), "n-2", 0.31, "更沾边"));

        RetrievalResult result = EvidenceAssembler.assemble(hits, Map.of(noisy.id(), noisy),
                Map.of(noisy.id(), PUBLIC), ADMIN, context(5));

        assertEquals(1, result.evidences().size(), "证据仍然保留，交给上游决定是否展示引用");
        assertTrue(result.insufficient());
        assertTrue(result.hint().contains("相关性偏低"), result.hint());
        assertEquals(0.45, result.diagnostics().answerMinScore(), 1e-9);
    }

    @Test
    @DisplayName("作答下限之上的命中不因阈值而误判为证据不足")
    void scoreAboveAnswerFloorIsAnswerable() {
        DocumentRef good = doc(1, "QM-ZD-2026-003", "V3.2", "INDEXED");

        RetrievalResult result = EvidenceAssembler.assemble(
                List.of(hit(good.id(), "g-1", 0.46, "刚好过线")),
                Map.of(good.id(), good), Map.of(good.id(), PUBLIC), ADMIN, context(1));

        assertFalse(result.insufficient());
    }

    @Test
    @DisplayName("hits 为 null 也不炸：当成零命中处理")
    void toleratesNullHits() {
        RetrievalResult result = EvidenceAssembler.assemble(null, Map.of(), Map.of(), ADMIN, context(5));

        assertTrue(result.insufficient());
        assertEquals(List.of(), result.evidences());
    }

    @Test
    @DisplayName("检索侧在 SQL 里挡下的命中也要计入无权限：用户该做的是找管理员，不是换关键词")
    void blockedHitsReportedByRetrieverCountAsUnauthorized() {
        DocumentRef visible = doc(1, "QM-ZD-2026-011", "V1.4", "INDEXED");

        RetrievalResult result = EvidenceAssembler.assemble(
                List.of(hit(visible.id(), "v-1", 0.70, "公开内容")),
                Map.of(visible.id(), visible), Map.of(visible.id(), PUBLIC), DEMO,
                context(5, 2));

        assertEquals(1, result.evidences().size());
        assertEquals(2, result.diagnostics().droppedUnauthorized());
        assertTrue(result.accessNotice().contains("无权访问"), result.accessNotice());
    }

    @Test
    @DisplayName("一条都没带回来、但检索侧挡下了命中：要说\"没有访问权限\"，不能说\"没找到\"")
    void allBlockedHitsExplainedAsPermission() {
        RetrievalResult result = EvidenceAssembler.assemble(List.of(), Map.of(), Map.of(), DEMO, context(5, 3));

        assertTrue(result.insufficient());
        assertTrue(result.hint().contains("访问权限"), result.hint());
        assertFalse(result.hint().contains("没有找到"), result.hint());
    }

    @Test
    @DisplayName("同一份文档命中很多片段时只留分最高的 6 条：全喂给模型会把它逼成复述，输出被截断反而答不出来")
    void capsFragmentsPerEvidence() {
        DocumentRef document = doc(3, "QM-ZD-2026-003", "V3.2", "INDEXED");
        List<RetrievedPassage> hits = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            hits.add(hit(document.id(), "c-" + i, 0.5 + i * 0.01, "第 " + i + " 段"));
        }

        RetrievalResult result = EvidenceAssembler.assemble(hits, Map.of(document.id(), document),
                Map.of(document.id(), PUBLIC), ADMIN, context(5));

        // 砍到 3 条会把"答案行排在第 5 个片段上"的漏答问题（Q19）做出来，所以上限是 6 不是 3
        assertEquals(6, result.evidences().get(0).fragments().size());
        assertEquals(List.of("c-9", "c-8", "c-7", "c-6", "c-5", "c-4"),
                result.evidences().get(0).fragments().stream().map(Evidence.Fragment::chunkId).toList());
    }

    @Test
    @DisplayName("片段序号随证据一起出来：账本靠 (document_id, chunk_index) 回查原文——"
            + "chunk_id 是代理键（重解析会变），序号是自然键；没有本地序号就留空，不编造")
    void carriesChunkIndexToFragment() {
        DocumentRef document = doc(3, "QM-ZD-2026-003", "V3.2", "INDEXED");
        RetrievedPassage local = new RetrievedPassage("11", 7, document.id(), "自建片段", 0.62, 0.38,
                "2026-09-29", 12, 34, 4, 4, "第三章", "PARAGRAPH", 0.5, 1, 1, 0.03, null);
        RetrievedPassage other = hit(document.id(), "k-1", 0.55, "另一片段");

        RetrievalResult result = EvidenceAssembler.assemble(List.of(local, other),
                Map.of(document.id(), document), Map.of(document.id(), PUBLIC), ADMIN, context(5));

        List<Evidence.Fragment> fragments = result.evidences().get(0).fragments();
        assertEquals(Integer.valueOf(7), fragmentOf(fragments, "11").chunkIndex());
        assertNull(fragmentOf(fragments, "k-1").chunkIndex());
    }

    @Test
    @DisplayName("有重排分时文档按重排分排序：余弦更高但重排更低的文档被压到后面（重排正是为纠正这个）")
    void ranksDocumentsByRerankScoreWhenPresent() {
        DocumentRef cosineTop = doc(1, "A-1", "V1.0", "INDEXED");
        DocumentRef rerankTop = doc(2, "A-2", "V1.0", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                local(cosineTop.id(), "a-1", 0.80, 0.15, "余弦高但答非所问"),
                local(rerankTop.id(), "b-1", 0.55, 0.92, "余弦一般但真相关"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(cosineTop.id(), cosineTop, rerankTop.id(), rerankTop),
                Map.of(cosineTop.id(), PUBLIC, rerankTop.id(), PUBLIC), ADMIN, context(5));

        assertEquals(List.of("A-2", "A-1"), result.evidences().stream().map(Evidence::docNo).toList());
        assertTrue(result.diagnostics().rerankApplied());
        assertEquals(0.92, result.diagnostics().bestRerankScore(), 1e-9);
        assertEquals(0.80, result.diagnostics().bestScore(), 1e-9, "bestScore 仍是全局最强余弦，不随重排换人");
    }

    @Test
    @DisplayName("没有重排分时逐位退化为旧顺序：comparing·重排开关关掉/熔断时行为与引入重排前一致")
    void withoutRerankScoreFallsBackToCosineOrder() {
        DocumentRef best = doc(1, "A-1", "V1.0", "INDEXED");
        DocumentRef last = doc(3, "A-3", "V1.0", "INDEXED");
        List<RetrievedPassage> hits = List.of(
                hit(last.id(), "l-1", 0.50, "低"),
                hit(best.id(), "b-1", 0.90, "高"));

        RetrievalResult result = EvidenceAssembler.assemble(hits,
                Map.of(best.id(), best, last.id(), last),
                Map.of(best.id(), PUBLIC, last.id(), PUBLIC), ADMIN, context(5));

        assertEquals(List.of("A-1", "A-3"), result.evidences().stream().map(Evidence::docNo).toList());
        assertFalse(result.diagnostics().rerankApplied());
        assertEquals(0d, result.diagnostics().bestRerankScore(), 1e-9);
    }

    private static Evidence.Fragment fragmentOf(List<Evidence.Fragment> fragments, String chunkId) {
        return fragments.stream().filter(fragment -> chunkId.equals(fragment.chunkId())).findFirst().orElseThrow();
    }

    private static EvidenceAssembler.Context context(int topN) {
        return context(topN, 0);
    }

    private static EvidenceAssembler.Context context(int topN, int blockedHitCount) {
        return new EvidenceAssembler.Context(0.25, 0.45, 0.35, topN * 3, topN, 12L, blockedHitCount);
    }

    private static DocumentRef doc(long id, String docNo, String version, String chunkStatus) {
        return new DocumentRef(id, docNo, docNo + " 的标题", version, "现行", "内部公开", "财务部", chunkStatus);
    }

    private static RetrievedPassage hit(long documentId, String chunkId, double score, String text) {
        return new RetrievedPassage(chunkId, null, documentId, text, score, 1d - score, "2026-09-29", 12, 34,
                null, null, null, null, 0, 0, 0, 0, null);
    }

    /** 自建通道的命中：带页码/两路名次/重排分。rerankScore 为 null 表示本次没走重排。 */
    private static RetrievedPassage local(long documentId, String chunkId, double score, Double rerankScore,
                                          String text) {
        return new RetrievedPassage(chunkId, 0, documentId, text, score, 1d - score, "2026-09-29", 12, 34,
                null, null, null, "PARAGRAPH", 0, 1, 0, 0.05, rerankScore);
    }
}
