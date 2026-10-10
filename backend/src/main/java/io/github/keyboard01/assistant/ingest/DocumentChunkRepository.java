package io.github.keyboard01.assistant.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.parse.ParsedChunk;
import io.github.keyboard01.assistant.retrieval.ChunkRow;
import io.github.keyboard01.assistant.security.AccessFilter;

/**
 * 片段仓库：自建解析的落点，也是自建检索的取数口。
 *
 * <p>权限在这里就下推到 SQL（join document 后用 {@link AccessFilter} 的条件）：
 * 不可见的文档根本不进候选。Java 侧还会再过一遍 ACL（纵深防御），但"先挡住"和"召回后再丢"
 * 不是一回事——后者会让不可见内容出现在候选集与账本里。</p>
 */
@Repository
public class DocumentChunkRepository {

    /** 检索查询共用的列。 */
    private static final String COLUMNS = """
            c.id as chunk_id, c.document_id, c.chunk_index, c.content, c.section_path, c.page_from, c.page_to,
            c.kind, d.doc_no, d.title, d.version, d.doc_status, d.secrecy, d.owner_dept, d.kernel_doc_name
            """;

    private static final RowMapper<ChunkRow> ROW = (rs, rowNum) -> new ChunkRow(
            rs.getLong("chunk_id"),
            rs.getLong("document_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getString("section_path"),
            (Integer) rs.getObject("page_from"),
            (Integer) rs.getObject("page_to"),
            rs.getString("kind"),
            rs.getString("doc_no"),
            rs.getString("title"),
            rs.getString("version"),
            rs.getString("doc_status"),
            rs.getString("secrecy"),
            rs.getString("owner_dept"),
            rs.getString("kernel_doc_name"),
            rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    public DocumentChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countByDocument(long documentId) {
        Integer count = jdbc.queryForObject("select count(*) from document_chunk where document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    /**
     * 已有片段的指纹序列（{@code content_hash:context_hash}），用于判断"内容与上下文都没变"。
     *
     * <p>把上下文哈希一起纳入：只比正文哈希的话，改了上下文增强开关或换了模型、上下文变了，
     * 却因为正文没变而被跳过，重嵌就成了空话。无上下文时后半段为空，与引入该能力前的指纹等价。</p>
     */
    public List<String> fingerprints(long documentId) {
        return jdbc.queryForList("""
                select coalesce(content_hash, '') || ':' || coalesce(context_hash, '') from document_chunk
                 where document_id = ? order by chunk_index
                """, String.class, documentId);
    }

    public List<ChunkRow> findByDocument(long documentId) {
        return jdbc.query("select " + COLUMNS + ", 0 as score from document_chunk c"
                + " join document d on d.id = c.document_id where c.document_id = ? order by c.chunk_index",
                ROW, documentId);
    }

    /**
     * 按 {@code (document_id, chunk_index)} 覆盖写，而不是"整份删掉再灌一遍"。
     *
     * <p>整份重建会给每条片段发一个新的 bigserial id，历史账本里的
     * {@code qa_trace_evidence.chunk_id} 当场变成悬空引用（实测 2987 条里 2554 条）。
     * 改成 upsert 之后，同一个位置上的片段沿用原来的 id：一次重解析不会让过去的引用全断，
     * 而真正被删掉的尾部片段在账本里"查不到"是**正确**结论——它确实不存在了。</p>
     *
     * <p>{@code embedded_at} 每次重写都刷新（"什么时候算出来的"），{@code created_at} 保留首次入库时间
     * （"什么时候第一次见到它"）——两个问题不一样，合成一个就答不了前者。</p>
     *
     * <p>SQL 拎成常量是为了能被单测盯住：{@code upsert} 的语义一半在 Java（片段顺序）一半在
     * {@code on conflict} 这半句上，少了它就是"删了重灌"的老行为，而这件事从接口上看不出来。</p>
     */
    static final String UPSERT_SQL = """
            insert into document_chunk (document_id, chunk_index, section_path, page_from, page_to,
                                        char_count, content_hash, preview, content, kind, level, embed_model,
                                        context_prefix, context_hash, embedded_at, embedding)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), cast(? as vector))
            on conflict (document_id, chunk_index) do update set
                section_path   = excluded.section_path,
                page_from      = excluded.page_from,
                page_to        = excluded.page_to,
                char_count     = excluded.char_count,
                content_hash   = excluded.content_hash,
                preview        = excluded.preview,
                content        = excluded.content,
                kind           = excluded.kind,
                level          = excluded.level,
                embed_model    = excluded.embed_model,
                context_prefix = excluded.context_prefix,
                context_hash   = excluded.context_hash,
                embedded_at    = now(),
                embedding      = excluded.embedding
            """;

    /** 一条待写库的片段：正文哈希与上下文（含其哈希）一起，构成幂等指纹。 */
    public record IndexedChunk(ParsedChunk chunk, String contentHash, String contextPrefix, String contextHash) {
    }

    /** 片段幂等指纹 = 正文哈希 + ':' + 上下文哈希（无上下文时后半段为空）。 */
    public static String fingerprint(IndexedChunk item) {
        return item.contentHash() + ":" + (item.contextHash() == null ? "" : item.contextHash());
    }

    public void upsert(long documentId, List<IndexedChunk> chunks, List<float[]> vectors, String model) {
        List<Object[]> batch = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            IndexedChunk item = chunks.get(i);
            ParsedChunk chunk = item.chunk();
            // 15 个值对应 SQL 里的 15 个列（embedded_at 用 now()，不占位）；少一个就会在批处理时报错。
            batch.add(new Object[]{
                    documentId, chunk.index(), chunk.sectionPath(), chunk.pageFrom(), chunk.pageTo(),
                    chunk.content().length(), item.contentHash(), preview(chunk.content()), chunk.content(),
                    chunk.kind(), chunk.level(), model, item.contextPrefix(), item.contextHash(),
                    literal(vectors.get(i))});
        }
        jdbc.batchUpdate(UPSERT_SQL, batch);
    }

    /**
     * 重解析后片段变少：删掉尾部多出来的那些（{@code chunk_index >= fromIndex}）。
     *
     * <p>不删整份是刻意的——尾部之外的行必须保 id，否则一次"解析器只改动最后一屏"的升级
     * 会把前面所有引用一起打断。</p>
     */
    public int deleteFrom(long documentId, int fromIndex) {
        return jdbc.update("delete from document_chunk where document_id = ? and chunk_index >= ?",
                documentId, fromIndex);
    }

    /**
     * 向量通道：余弦距离 KNN。用 {@code <=>} 让 pgvector 走 HNSW 索引；
     * 分数统一成余弦相似度（1 - 距离），和"作答阈值"是同一个量纲。
     */
    /**
     * 一条待执行的查询：SQL 与它的绑定值。
     *
     * <p>分开返回是为了能单测一件事——**占位符数量必须等于绑定值数量**。
     * 这类错误只在运行时炸，而且要连上库、走到那条分支才看得见（报错是"未设定参数值 N 的内容"），
     * 纯靠读代码很难发现。</p>
     */
    record Query(String sql, Object[] params) {
    }

    public List<ChunkRow> searchVector(AccessFilter filter, String domainCode, float[] queryVector, int topN,
                                       double minScore) {
        Query query = vectorQuery(filter, domainCode, literal(queryVector), topN, minScore);
        return jdbc.query(query.sql(), ROW, query.params());
    }

    static Query vectorQuery(AccessFilter filter, String domainCode, String vectorLiteral, int topN,
                             double minScore) {
        // 查询向量按**文本顺序**绑定三次：取分数一次、比阈值一次、排序一次。
        // 三处都得是同一个常量，但 pgvector 只认 `列 <=> 常量` 这种形状才会走 HNSW 索引，
        // 所以不能用 CTE/别名把它折叠成一次——省下的那点带宽，换不回一次全表扫描。
        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral);
        params.add(domainCode);
        for (Object param : filter.paramsArray()) {
            params.add(param);
        }
        params.add(vectorLiteral);
        params.add(minScore);
        params.add(vectorLiteral);
        params.add(topN);
        String sql = """
                select %s, 1 - (c.embedding <=> cast(? as vector)) as score
                  from document_chunk c
                  join document d on d.id = c.document_id
                 where d.domain_code = ? and c.embedding is not null and %s
                   and 1 - (c.embedding <=> cast(? as vector)) >= ?
                 order by c.embedding <=> cast(? as vector)
                 limit ?
                """.formatted(COLUMNS, filter.sql());
        return new Query(sql, params.toArray());
    }

    /** 关键词通道：trigram 相似度（短查询撞长正文时，word_similarity 比 similarity 稳）。 */
    public List<ChunkRow> searchKeyword(AccessFilter filter, String domainCode, String question, int topN,
                                        double minScore) {
        Query query = keywordQuery(filter, domainCode, question, topN, minScore);
        return jdbc.query(query.sql(), ROW, query.params());
    }

    static Query keywordQuery(AccessFilter filter, String domainCode, String question, int topN, double minScore) {
        List<Object> params = new ArrayList<>();
        params.add(question);
        params.add(domainCode);
        for (Object param : filter.paramsArray()) {
            params.add(param);
        }
        params.add(question);
        params.add(minScore);
        params.add(topN);
        String sql = """
                select %s, word_similarity(?, c.content) as score
                  from document_chunk c
                  join document d on d.id = c.document_id
                 where d.domain_code = ? and c.content is not null and %s
                   and word_similarity(?, c.content) >= ?
                 order by score desc
                 limit ?
                """.formatted(COLUMNS, filter.sql());
        return new Query(sql, params.toArray());
    }

    /**
     * "匹配上了、但当前身份看不见"的片段条数。
     *
     * <p>既要在 SQL 层把不可见的挡掉（不可见内容不该进候选集），又要能告诉用户"是权限问题"——
     * 那就用同一个查询向量、同一道下限，把条件反向取一次（{@code not (ACL)}），只要条数。
     * 这个扫描走不了索引，所以只在文档量级还小的阶段可用；量大了要改成"按文档分组计数并缓存"。</p>
     */
    public int countBlockedCandidates(AccessFilter filter, String domainCode, float[] queryVector,
                                      double minScore) {
        Query query = blockedQuery(filter, domainCode, literal(queryVector), minScore);
        Integer count = jdbc.queryForObject(query.sql(), Integer.class, query.params());
        return count == null ? 0 : count;
    }

    static Query blockedQuery(AccessFilter filter, String domainCode, String vectorLiteral, double minScore) {
        List<Object> params = new ArrayList<>();
        params.add(domainCode);
        for (Object param : filter.paramsArray()) {
            params.add(param);
        }
        params.add(vectorLiteral);
        params.add(minScore);
        String sql = """
                select count(*) from document_chunk c
                  join document d on d.id = c.document_id
                 where d.domain_code = ? and c.embedding is not null and not (%s)
                   and 1 - (c.embedding <=> cast(? as vector)) >= ?
                """.formatted(filter.sql());
        return new Query(sql, params.toArray());
    }

    /** pgvector 的参数只能以字面量形式传（没有 JDBC 类型映射），拼之前先确认没有 NaN/Infinity。 */
    static String literal(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalStateException("向量里有非法数值（NaN/Infinity），拒绝写库");
            }
            if (i > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.append(']').toString();
    }

    private static String preview(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }
}
