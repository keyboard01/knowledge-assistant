package io.github.keyboard01.assistant.view;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.view.DocumentViewModels.Block;

/**
 * 查看用的片段读取口。
 *
 * <p>为什么不复用 {@code DocumentChunkRepository}：那一份是按"检索"取的（列里没有 {@code level}、
 * 带 score、带权限下推）。查看是按"文档顺序"取的，两边的列与语义都会各自演化，
 * 硬凑成一个查询以后谁也不敢改。这里只做一件事：把一份文档的片段按顺序取出来。</p>
 */
@Repository
public class ViewChunkRepository {

    private static final RowMapper<Block> MAPPER = (rs, rowNum) -> new Block(
            rs.getLong("id"),
            rs.getString("kind") == null ? "PARAGRAPH" : rs.getString("kind"),
            rs.getInt("level"),
            rs.getString("content"),
            (Integer) rs.getObject("page_from"),
            (Integer) rs.getObject("page_to"),
            rs.getString("section_path"));

    private final JdbcTemplate jdbc;

    public ViewChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Block> findByDocument(long documentId) {
        List<Block> blocks = jdbc.query("""
                select id, kind, coalesce(level, 1) as level, content, page_from, page_to, section_path
                  from document_chunk
                 where document_id = ? and content is not null and btrim(content) <> ''
                 order by chunk_index
                """, MAPPER, documentId);
        return blocks.stream()
                .map(block -> block.text() == null ? block : new Block(block.chunkId(), block.kind(), block.level(),
                        block.text().trim(), block.pageFrom(), block.pageTo(), block.sectionPath()))
                .toList();
    }
}
