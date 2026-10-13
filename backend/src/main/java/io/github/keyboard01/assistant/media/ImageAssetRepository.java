package io.github.keyboard01.assistant.media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.DocumentVisibility;

/**
 * 图片素材仓。检索 SQL 与 `DocumentChunkRepository` 同形：`join document` 后内联
 * {@link AccessFilter} 的权限下推与 {@link DocumentVisibility} 的"现行"谓词——
 * **图片的可见性完全由所属文档决定，不另立权限路径**。
 */
@Repository
public class ImageAssetRepository {

    /** 检索 SQL 与绑定参数分开放，便于单测断言"占位符数 == 参数数"。 */
    record Query(String sql, Object[] params) {
    }

    private static final String HIT_COLUMNS = """
            a.id as asset_id, a.document_id, a.page, a.ext, a.caption,
            d.doc_no, d.title, d.version, d.doc_status, d.secrecy
            """;

    private static final RowMapper<MediaModels.Hit> HIT_MAPPER = (rs, rowNum) -> new MediaModels.Hit(
            rs.getLong("asset_id"),
            rs.getLong("document_id"),
            rs.getString("doc_no"),
            rs.getString("title"),
            rs.getString("version"),
            (Integer) rs.getObject("page"),
            rs.getString("ext"),
            rs.getString("caption"),
            rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    public ImageAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MediaModels.Asset> findByDocument(long documentId) {
        return jdbc.query("""
                select id, document_id, asset_index, page, ext, width, height, byte_size, content_hash,
                       caption, ocr_text, search_text, embed_model, vision_embed_model
                  from image_asset where document_id = ? order by asset_index
                """, (rs, rowNum) -> new MediaModels.Asset(
                rs.getLong("id"), rs.getLong("document_id"), rs.getInt("asset_index"),
                (Integer) rs.getObject("page"), rs.getString("ext"), rs.getInt("width"), rs.getInt("height"),
                rs.getLong("byte_size"), rs.getString("content_hash"), rs.getString("caption"),
                rs.getString("ocr_text"), rs.getString("search_text"), rs.getString("embed_model"),
                rs.getString("vision_embed_model")),
                documentId);
    }

    public Optional<MediaModels.Asset> findById(long id) {
        return jdbc.query("""
                select id, document_id, asset_index, page, ext, width, height, byte_size, content_hash,
                       caption, ocr_text, search_text, embed_model, vision_embed_model
                  from image_asset where id = ?
                """, (rs, rowNum) -> new MediaModels.Asset(
                rs.getLong("id"), rs.getLong("document_id"), rs.getInt("asset_index"),
                (Integer) rs.getObject("page"), rs.getString("ext"), rs.getInt("width"), rs.getInt("height"),
                rs.getLong("byte_size"), rs.getString("content_hash"), rs.getString("caption"),
                rs.getString("ocr_text"), rs.getString("search_text"), rs.getString("embed_model"),
                rs.getString("vision_embed_model")),
                id).stream().findFirst();
    }

    /**
     * 幂等指纹：`内容哈希 : 文本模型 : 视觉模型`。任一变化才重算该图。
     * 与 {@link #fingerprint} 必须保持同一形状（SQL 里拼的与 Java 里拼的要一致）。
     */
    public Map<Integer, String> fingerprints(long documentId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        jdbc.query("""
                select asset_index,
                       coalesce(content_hash, '') || ':' || coalesce(embed_model, '') || ':' ||
                       coalesce(nullif(vision_embed_model, ''), '-') as fp
                  from image_asset where document_id = ? order by asset_index
                """, (rs, rowNum) -> Map.entry(rs.getInt("asset_index"), rs.getString("fp")), documentId)
                .forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    /** 指纹的 Java 侧拼法，必须与 {@link #fingerprints} 的 SQL 一致。 */
    public static String fingerprint(String contentHash, String embedModel, String visionModel) {
        return (contentHash == null ? "" : contentHash) + ":"
                + (embedModel == null ? "" : embedModel) + ":"
                + (visionModel == null || visionModel.isBlank() ? "-" : visionModel);
    }

    /** 按 `(document_id, asset_index)` 覆盖写，id 不换（尾删由 {@link #deleteFrom} 负责）。 */
    public void upsert(long documentId, List<MediaModels.AssetInput> assets) {
        for (MediaModels.AssetInput asset : assets) {
            jdbc.update(UPSERT_SQL, documentId, asset.assetIndex(), asset.page(), asset.ext(), asset.width(),
                    asset.height(), asset.byteSize(), asset.contentHash(), asset.caption(), asset.ocrText(),
                    asset.searchText(), asset.embedModel(), literal(asset.embedding()),
                    asset.visionEmbedModel(), vectorOrNull(asset.visionEmbedding()));
        }
    }

    private static String vectorOrNull(float[] vector) {
        return vector == null ? null : literal(vector);
    }

    static final String UPSERT_SQL = """
            insert into image_asset (document_id, asset_index, page, ext, width, height, byte_size,
                                     content_hash, caption, ocr_text, search_text, embed_model, embedding,
                                     vision_embed_model, vision_embedding)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?, cast(? as vector))
            on conflict (document_id, asset_index) do update set
                page = excluded.page, ext = excluded.ext, width = excluded.width, height = excluded.height,
                byte_size = excluded.byte_size, content_hash = excluded.content_hash,
                caption = excluded.caption, ocr_text = excluded.ocr_text, search_text = excluded.search_text,
                embed_model = excluded.embed_model, embedding = excluded.embedding,
                vision_embed_model = excluded.vision_embed_model, vision_embedding = excluded.vision_embedding
            """;

    public int deleteFrom(long documentId, int fromIndex) {
        return jdbc.update("delete from image_asset where document_id = ? and asset_index >= ?",
                documentId, fromIndex);
    }

    public List<MediaModels.Hit> searchVector(AccessFilter filter, String domainCode, float[] queryVector,
                                              int topN, double minScore) {
        Query query = vectorQuery(filter, domainCode, literal(queryVector), topN, minScore);
        return jdbc.query(query.sql(), HIT_MAPPER, query.params());
    }

    public List<MediaModels.Hit> searchKeyword(AccessFilter filter, String domainCode, String question, int topN,
                                               double minScore) {
        Query query = keywordQuery(filter, domainCode, question, topN, minScore);
        return jdbc.query(query.sql(), HIT_MAPPER, query.params());
    }

    /** 视觉向量通道：查询文本由**同一个多模态模型**编码后进来（跨模态：文本↔图片同一空间）。 */
    public List<MediaModels.Hit> searchVision(AccessFilter filter, String domainCode, float[] queryVector,
                                              int topN, double minScore) {
        Query query = visionVectorQuery(filter, domainCode, literal(queryVector), topN, minScore);
        return jdbc.query(query.sql(), HIT_MAPPER, query.params());
    }

    static Query visionVectorQuery(AccessFilter filter, String domainCode, String vectorLiteral, int topN,
                                   double minScore) {
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
                select %s, 1 - (a.vision_embedding <=> cast(? as vector)) as score
                  from image_asset a
                  join document d on d.id = a.document_id
                 where d.domain_code = ? and a.vision_embedding is not null and %s and %s
                   and 1 - (a.vision_embedding <=> cast(? as vector)) >= ?
                 order by a.vision_embedding <=> cast(? as vector)
                 limit ?
                """.formatted(HIT_COLUMNS, filter.sql(), DocumentVisibility.currentSql("d"));
        return new Query(sql, params.toArray());
    }

    static Query vectorQuery(AccessFilter filter, String domainCode, String vectorLiteral, int topN,
                             double minScore) {
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
                select %s, 1 - (a.embedding <=> cast(? as vector)) as score
                  from image_asset a
                  join document d on d.id = a.document_id
                 where d.domain_code = ? and a.embedding is not null and %s and %s
                   and 1 - (a.embedding <=> cast(? as vector)) >= ?
                 order by a.embedding <=> cast(? as vector)
                 limit ?
                """.formatted(HIT_COLUMNS, filter.sql(), DocumentVisibility.currentSql("d"));
        return new Query(sql, params.toArray());
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
                select %s, word_similarity(?, a.search_text) as score
                  from image_asset a
                  join document d on d.id = a.document_id
                 where d.domain_code = ? and a.search_text is not null and %s and %s
                   and word_similarity(?, a.search_text) >= ?
                 order by score desc
                 limit ?
                """.formatted(HIT_COLUMNS, filter.sql(), DocumentVisibility.currentSql("d"));
        return new Query(sql, params.toArray());
    }

    /** pgvector 没有 JDBC 类型映射，只能以字面量传；拼之前先挡掉 NaN/Infinity。 */
    static String literal(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("向量含非法值（NaN/Infinity），拒绝写入与检索");
            }
            if (i > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.append(']').toString();
    }
}
