package io.github.keyboard01.assistant.retrieval;

import java.util.List;

/**
 * 一条证据 = 一份文档 + 它在本次检索里命中的那些片段。
 *
 * <p>同一份文档的多个命中片段聚合到一条证据里：片段按分数排序，每个片段的 chunkId、正文与页码都保留，
 * 引用才能指回具体位置。</p>
 */
public record Evidence(int rank, long documentId, String docNo, String title, String version, String docStatus,
                       String secrecy, String ownerDept, double bestScore,
                       List<Fragment> fragments) {

    /**
     * 片段。{@code score} 是向量余弦相似度（0 表示只被关键词通道命中），
     * {@code keywordScore} 与两路名次只用于解释与排障。
     *
     * <p>{@code pageFrom}/{@code pageTo} 为空表示**没有页码信息**（DOCX 没有页码语义，
     * 或内核来的片段带不回来）。界面在这种情况下不显示页码——编一个页码比没有页码更糟。</p>
     */
    public record Fragment(String chunkId, Integer chunkIndex, String text, double score, double distance,
                           int wordCount, Integer pageFrom, Integer pageTo, String sectionPath, String kind,
                           double keywordScore, int vectorRank, int keywordRank) {

        /** P1 的调用方式：不带页码与两路名次。 */
        public Fragment(String chunkId, String text, double score, double distance, int wordCount) {
            this(chunkId, null, text, score, distance, wordCount, null, null, null, null, 0, 0, 0);
        }
    }
}
