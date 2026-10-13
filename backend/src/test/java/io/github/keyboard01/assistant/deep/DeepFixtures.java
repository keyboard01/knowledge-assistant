package io.github.keyboard01.assistant.deep;

import java.util.ArrayList;
import java.util.List;

import io.github.keyboard01.assistant.retrieval.Evidence;

/** 深度通道的单测夹具：证据对象字段多，散在每个测试里手搓会看不出"这个测试到底在测什么"。 */
final class DeepFixtures {

    private DeepFixtures() {
    }

    static Evidence.Fragment fragment(String chunkId, Integer chunkIndex, String text, double score) {
        return new Evidence.Fragment(chunkId, chunkIndex, text, score, 1 - score, text.length(), chunkIndex, null,
                "第一章", "PARAGRAPH", 0d, 0, 0);
    }

    /** rank 传 0：探索阶段的证据还没有全局编号，编号由合并阶段分配。 */
    static Evidence document(long documentId, String docNo, String title, double bestScore,
                             Evidence.Fragment... fragments) {
        return new Evidence(0, documentId, docNo, title, "V1.0", "现行", "内部公开", "生产部",
                bestScore, List.of(fragments));
    }

    static List<Evidence> list(Evidence... evidences) {
        return new ArrayList<>(List.of(evidences));
    }
}
