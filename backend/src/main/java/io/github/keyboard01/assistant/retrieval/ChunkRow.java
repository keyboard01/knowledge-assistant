package io.github.keyboard01.assistant.retrieval;

/**
 * 自建检索的一条候选：本地片段 + 它所属文档的元数据 + 这一路的分数。
 * 一条 SQL 就把权限过滤做掉，所以能走到这里的片段，都是当前身份可见的。
 */
public record ChunkRow(long chunkId, long documentId, int chunkIndex, String content, String sectionPath,
                       Integer pageFrom, Integer pageTo, String kind, String docNo, String title, String version,
                       String docStatus, String secrecy, String ownerDept, double score) {
}