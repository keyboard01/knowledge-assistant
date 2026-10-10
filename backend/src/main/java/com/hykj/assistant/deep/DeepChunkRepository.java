package com.hykj.assistant.deep;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 深度通道的片段读取口（按文档顺序取）。
 *
 * <p>为什么不复用检索那一份 {@code DocumentChunkRepository}：那一份是按"检索"设计的（关键词/向量下推、
 * 带分数、只取 Top-N），这里是按"顺序读后续片段"设计的，两者的列与语义会各自演化。
 * 也不复用查看模块的 {@code ViewChunkRepository}：它不带 {@code chunk_index}，而"从第几段往后读"
 * 正是本类存在的理由。</p>
 */
@Repository
public class DeepChunkRepository {

    /** 空片段整体跳过：它们进提示词只是占 token，进引用只是指不到东西。 */
    private static final String NOT_EMPTY = "content is not null and btrim(content) <> ''";

    public record Chunk(long chunkId, int chunkIndex, String content, String kind, Integer pageFrom,
                        Integer pageTo, String sectionPath) {
    }

    private static final RowMapper<Chunk> MAPPER = (rs, rowNum) -> new Chunk(
            rs.getLong("id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getString("kind"),
            (Integer) rs.getObject("page_from"),
            (Integer) rs.getObject("page_to"),
            rs.getString("section_path"));

    private final JdbcTemplate jdbc;

    public DeepChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Chunk> findFrom(long documentId, int fromChunkIndex, int limit) {
        return jdbc.query("""
                select id, chunk_index, content, kind, page_from, page_to, section_path
                  from document_chunk
                 where document_id = ? and chunk_index >= ? and
                """ + NOT_EMPTY + " order by chunk_index limit ?", MAPPER, documentId, fromChunkIndex, limit);
    }

    public boolean hasMore(long documentId, int afterChunkIndex) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from document_chunk
                                where document_id = ? and chunk_index > ? and
                """ + NOT_EMPTY + ") as more", Boolean.class, documentId, afterChunkIndex);
        return Boolean.TRUE.equals(exists);
    }

}
