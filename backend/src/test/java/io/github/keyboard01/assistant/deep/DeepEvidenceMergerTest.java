package io.github.keyboard01.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.deep.DeepEvidenceMerger.Merged;
import io.github.keyboard01.assistant.deep.DeepEvidenceMerger.Round;
import io.github.keyboard01.assistant.retrieval.Evidence;

/**
 * 合并口径错了会**静默**漏证据或错引：所以去重、编号、切片、上限四件事都要有断言。
 */
class DeepEvidenceMergerTest {

    @Test
    @DisplayName("同一片段被多轮带回来只保留一条：重复片段会让模型以为它被强调了")
    void deduplicatesFragments() {
        Evidence first = document(8, "QM-WX-GA75-2026", "维修手册", 0.7, fragment("c-1", 10, "表头", 0.7));
        Evidence again = document(8, "QM-WX-GA75-2026", "维修手册", 0.6, fragment("c-9", 10, "表头", 0.6));

        Merged merged = DeepEvidenceMerger.merge(List.of(new Round("Q1", List.of(first)),
                new Round("Q1", List.of(again))));

        assertEquals(1, merged.evidences().size());
        assertEquals(1, merged.evidences().get(0).fragments().size());
        assertEquals(0.7, merged.evidences().get(0).bestScore(), 1e-9);
    }

    @Test
    @DisplayName("全局编号按最强分重排：多轮拼接的两套 [1] 必须消掉")
    void renumbersGlobally() {
        Evidence weak = document(8, "B-002", "后一份", 0.5, fragment("c-1", 1, "弱", 0.5));
        Evidence strong = document(5, "A-001", "前一份", 0.9, fragment("c-2", 3, "强", 0.9));

        Merged merged = DeepEvidenceMerger.merge(List.of(new Round("Q1", List.of(weak, strong))));

        assertEquals(List.of(1, 2), merged.evidences().stream().map(Evidence::rank).toList());
        assertEquals(List.of(5L, 8L), merged.evidences().stream().map(Evidence::documentId).toList());
    }

    @Test
    @DisplayName("切片只装自己那一问摸到的片段，但编号仍是全局编号")
    void slicesKeepGlobalRanks() {
        Evidence first = document(5, "A-001", "第一份", 0.9, fragment("c-1", 1, "第一份内容", 0.9));
        Evidence second = document(8, "B-002", "第二份", 0.5, fragment("c-2", 2, "第二份内容", 0.5));

        Merged merged = DeepEvidenceMerger.merge(List.of(new Round("Q1", List.of(first)),
                new Round("Q2", List.of(second))));

        assertEquals(2, merged.evidences().size());
        assertEquals(1, merged.slicesBySubQuestion().get("Q1").size());
        assertEquals(1, merged.slicesBySubQuestion().get("Q1").get(0).rank());
        assertEquals(5, merged.slicesBySubQuestion().get("Q1").get(0).documentId());
        assertEquals("第一份内容", merged.slicesBySubQuestion().get("Q1").get(0).fragments().get(0).text());
        assertEquals(2, merged.slicesBySubQuestion().get("Q2").get(0).rank());
        assertEquals(8, merged.slicesBySubQuestion().get("Q2").get(0).documentId());
        assertEquals("第二份内容", merged.slicesBySubQuestion().get("Q2").get(0).fragments().get(0).text());
        assertTrue(merged.covered("Q1"));
    }

    @Test
    @DisplayName("单份文档片段上限 16：留下的必须是分数最高的那些，而不是碰巧先到的")
    void capsFragmentsPerDocument() {
        List<Evidence.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fragments.add(fragment("c-" + i, i, "片段 " + i, i / 100d));
        }
        Evidence document = DeepFixtures.document(8, "B-002", "手册", 0.19,
                fragments.toArray(new Evidence.Fragment[0]));

        Merged merged = DeepEvidenceMerger.merge(List.of(new Round("Q1", List.of(document))));

        List<Evidence.Fragment> kept = merged.evidences().get(0).fragments();
        assertEquals(DeepEvidenceMerger.MAX_FRAGMENTS_PER_DOCUMENT, kept.size());
        assertEquals(0.19, kept.get(0).score(), 1e-9);
        assertTrue(kept.stream().noneMatch(fragment -> fragment.score() < 0.04));
    }

    @Test
    @DisplayName("没有序号信息的片段退回 chunkId 做自然键")
    void fallsBackToChunkId() {
        Evidence one = document(8, "B-002", "手册", 0.5, fragment("c-7", null, "同一段", 0.5));
        Evidence two = document(8, "B-002", "手册", 0.4, fragment("c-7", null, "同一段", 0.4));

        Merged merged = DeepEvidenceMerger.merge(List.of(new Round("Q1", List.of(one)),
                new Round("Q2", List.of(two))));

        assertEquals(1, merged.evidences().get(0).fragments().size());
    }

    private static Evidence document(long id, String docNo, String title, double score,
                                     Evidence.Fragment... fragments) {
        return DeepFixtures.document(id, docNo, title, score, fragments);
    }

    private static Evidence.Fragment fragment(String chunkId, Integer chunkIndex, String text, double score) {
        return DeepFixtures.fragment(chunkId, chunkIndex, text, score);
    }
}
