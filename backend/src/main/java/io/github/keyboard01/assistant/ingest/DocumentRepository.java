package io.github.keyboard01.assistant.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.DocumentVisibility;

@Repository
public class DocumentRepository {

    public record Row(long id, String domainCode, String docNo, String title, String version,
                      String docStatus, String secrecy, String ownerDept, String sourcePath,
                      String contentHash, Long fileSize, String mimeType,
                      String chunkStatus, Integer chunkCount,
                      String lastError, String uploadedBy,
                      String kind, String assetStatus, Integer assetCount) {
    }

    private static final String COLUMNS = """
            id, domain_code, doc_no, title, version, doc_status, secrecy, owner_dept, source_path,
            content_hash, file_size, mime_type, chunk_status, chunk_count,
            last_error, uploaded_by, kind, asset_status, asset_count
            """;

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(
            rs.getLong("id"),
            rs.getString("domain_code"),
            rs.getString("doc_no"),
            rs.getString("title"),
            rs.getString("version"),
            rs.getString("doc_status"),
            rs.getString("secrecy"),
            rs.getString("owner_dept"),
            rs.getString("source_path"),
            rs.getString("content_hash"),
            (Long) rs.getObject("file_size"),
            rs.getString("mime_type"),
            rs.getString("chunk_status"),
            rs.getInt("chunk_count"),
            rs.getString("last_error"),
            rs.getString("uploaded_by"),
            rs.getString("kind"),
            rs.getString("asset_status"),
            rs.getInt("asset_count"));

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> findAll() {
        return jdbc.query("select " + COLUMNS + " from document order by domain_code, doc_no, version", MAPPER);
    }

    /**
     * 文档列表（先过域级 + 文档级 ACL）。
     *
     * <p>非管理员再收窄一层：只看到"现行 + 自己上传的草案"——别人的未审草案不该在列表里露出，
     * 但本人要能看到自己刚交的东西。管理员看全部，否则无法在列表里审核。</p>
     */
    public List<Row> findVisible(AccessFilter filter, String viewerUsername, boolean admin) {
        String order = " order by d.domain_code, d.doc_no, d.version";
        if (admin) {
            return jdbc.query("select " + COLUMNS + " from document d where " + filter.sql() + order,
                    MAPPER, filter.paramsArray());
        }
        Object[] params = append(filter.paramsArray(), viewerUsername);
        return jdbc.query("select " + COLUMNS + " from document d where " + filter.sql()
                + " and (" + DocumentVisibility.currentSql("d") + " or d.uploaded_by = ?)" + order,
                MAPPER, params);
    }

    private static Object[] append(Object[] base, Object extra) {
        Object[] merged = new Object[base.length + 1];
        System.arraycopy(base, 0, merged, 0, base.length);
        merged[base.length] = extra;
        return merged;
    }

    /**
     * 各知识域下"当前身份可见"的文档数（AC-P1-009）。
     *
     * <p>只返回可见数，**绝不返回总数**：总数等于告诉对方"这里还有你看不到的文档"，
     * 泄漏的不是内容而是"存在什么"。可见数为 0 时前端才能标出无权限，而不是等用户问了才发现。</p>
     */
    public Map<String, Integer> countVisibleByDomain(AccessFilter filter) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select d.domain_code, count(*) as visible from document d where " + filter.sql()
                        + " group by d.domain_code",
                (rs, rowNum) -> Map.entry(rs.getString("domain_code"), rs.getInt("visible")),
                filter.paramsArray())
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    public Optional<Row> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from document where id = ?", MAPPER, id).stream().findFirst();
    }

    /** 某知识域下的文档总数（含不可见）——删除知识域前的守卫用。 */
    public int countByDomain(String domainCode) {
        Integer count = jdbc.queryForObject("select count(*) from document where domain_code = ?",
                Integer.class, domainCode);
        return count == null ? 0 : count;
    }

    /** 现行 / 废止切换（版本状态）。只动状态字段，不重传、不重解析。 */
    public void updateStatus(long id, String docStatus) {
        jdbc.update("update document set doc_status = ?, updated_at = now() where id = ?", docStatus, id);
    }

    public Optional<Row> findByDocNoAndVersion(String docNo, String version) {
        return jdbc.query("select " + COLUMNS + " from document where doc_no = ? and version = ?",
                MAPPER, docNo, version).stream().findFirst();
    }

    /** 批量反查：检索命中的片段带着 {@code document_id}，这是把片段挂回本地登记的唯一入口。 */
    public List<Row> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("select " + COLUMNS + " from document where id in (" + placeholders + ")",
                MAPPER, ids.toArray());
    }

    public long insert(IngestManifest.Entry entry, String domainCode, String sourcePath, String contentHash,
                       long fileSize, String mimeType, String uploadedBy, String kind) {
        Long id = jdbc.queryForObject("""
                insert into document (domain_code, doc_no, title, version, doc_status, secrecy, owner_dept,
                                      source_path, content_hash, file_size, mime_type, effective_from,
                                      effective_to, uploaded_by, kind)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as date), cast(? as date), ?, ?)
                returning id
                """, Long.class,
                domainCode, entry.docNo(), entry.title(), entry.version(),
                blankTo(entry.docStatus(), "现行"), blankTo(entry.secrecy(), "内部公开"), entry.ownerDept(),
                sourcePath, contentHash, fileSize, mimeType,
                blankToNull(entry.effectiveFrom()), blankToNull(entry.effectiveTo()), uploadedBy,
                blankTo(kind, "text"));
        return id == null ? 0L : id;
    }

    /** 内容或元数据变了：先更新登记，再重新入库，避免出现"半新半旧"的行。 */
    public void updateSource(long id, IngestManifest.Entry entry, String domainCode, String sourcePath,
                             String contentHash, long fileSize, String mimeType,
                             String uploadedBy, String kind) {
        jdbc.update("""
                update document set domain_code = ?, title = ?, doc_status = ?, secrecy = ?, owner_dept = ?,
                       source_path = ?, content_hash = ?, file_size = ?, mime_type = ?, kind = ?,
                       effective_from = cast(? as date), effective_to = cast(? as date),
                       last_error = null, chunk_status = 'PENDING', chunk_count = 0, chunk_error = null,
                       asset_status = 'NONE', asset_count = 0, asset_error = null,
                       uploaded_by = coalesce(?, uploaded_by), updated_at = now()
                 where id = ?
                """,
                domainCode, entry.title(), blankTo(entry.docStatus(), "现行"),
                blankTo(entry.secrecy(), "内部公开"), entry.ownerDept(),
                sourcePath, contentHash, fileSize, mimeType, blankTo(kind, "text"),
                blankToNull(entry.effectiveFrom()), blankToNull(entry.effectiveTo()),
                uploadedBy, id);
    }

    /** 图片文档：不产 chunk——标 SKIPPED，避免看起来"缺索引"。 */
    public void markImageSkipped(long id) {
        jdbc.update("""
                update document set chunk_status = 'SKIPPED', last_error = null,
                       updated_at = now()
                 where id = ?
                """, id);
    }

    /** 图片资产就绪（与 chunk_status 平行）。 */
    public void markAssetIndexed(long id, int assetCount) {
        jdbc.update("""
                update document set asset_status = 'INDEXED', asset_count = ?, asset_error = null,
                       updated_at = now()
                 where id = ?
                """, assetCount, id);
    }

    public void markAssetFailed(long id, String error) {
        jdbc.update("""
                update document set asset_status = 'FAILED', asset_error = ?, updated_at = now()
                 where id = ?
                """, error, id);
    }

    /** 未启用视觉模型等情况：明确记为 SKIPPED（不是失败），便于区分"没做"与"做坏了"。 */
    public void markAssetSkipped(long id, String reason) {
        jdbc.update("""
                update document set asset_status = 'SKIPPED', asset_error = ?, updated_at = now()
                 where id = ?
                """, reason, id);
    }

    /** 自建片段就绪。这也是"文档可被检索"的唯一状态。 */
    public void markChunkIndexed(long id, int chunkCount) {
        jdbc.update("""
                update document set chunk_status = 'INDEXED', chunk_count = ?, chunk_error = null,
                       updated_at = now()
                 where id = ?
                """, chunkCount, id);
    }

    public void markChunkFailed(long id, String error) {
        jdbc.update("""
                update document set chunk_status = 'FAILED', chunk_error = ?, updated_at = now()
                 where id = ?
                """, error, id);
    }

    /**
     * 已就绪（片段可检索）的文本文档，重解析用。
     *
     * <p>用占位符而不是把 {@code 'INDEXED'} 拼进 SQL：原文写成了 {@code '\''INDEXED'\''}，
     * 而 Java 的字符串里 {@code '} 本来就不用转义，于是编译出来是 {@code '''INDEXED'''}——
     * 这是一个合法的 SQL 字面量（值等于 {@code 'INDEXED'}，带着引号），查询不报错，
     * 只是永远返回 0 行。整个重灌入口就这么静默空转了。占位符把这类引号事故从根上关掉。</p>
     */
    public List<Row> findChunkIndexed() {
        // 只重解析文本文档：图片文档没有 chunk，进这里只会白跑一遍
        return jdbc.query("select " + COLUMNS + " from document where chunk_status = ? and kind = 'text' "
                        + "order by id",
                MAPPER, "INDEXED");
    }

    /**
     * ACL 是 fail-closed 的：没有匹配行就查不到，所以每个入库文档都必须写上可见范围。
     * 这里只做最小规则（密级 → 角色），T06 会把它换成可配置的授权表。
     */
    public boolean replaceAcl(long documentId, String secrecy) {
        List<AclRule> rules = aclRules(secrecy);
        Set<String> expected = new LinkedHashSet<>();
        for (AclRule rule : rules) {
            expected.add(ruleKey(rule.subjectType(), rule.subjectValue(), "READ"));
        }
        Set<String> current = new LinkedHashSet<>(jdbc.queryForList("""
                select upper(subject_type) || '|' || subject_value || '|' || upper(permission) as rule_key
                  from document_acl where document_id = ?
                """, String.class, documentId));
        // 一样就不动：删除再插入会让 created_at 每次都刷新，"这条授权什么时候给的"就再也答不上来
        if (expected.equals(current)) {
            return false;
        }
        jdbc.update("delete from document_acl where document_id = ?", documentId);
        for (AclRule rule : rules) {
            jdbc.update("""
                    insert into document_acl (document_id, subject_type, subject_value, permission)
                    values (?, ?, ?, 'READ')
                    on conflict (document_id, subject_type, subject_value, permission) do nothing
                    """, documentId, rule.subjectType(), rule.subjectValue());
        }
        return true;
    }

    private static String ruleKey(String subjectType, String subjectValue, String permission) {
        return (subjectType == null ? "" : subjectType.trim().toUpperCase(java.util.Locale.ROOT))
                + "|" + (subjectValue == null ? "" : subjectValue.trim())
                + "|" + (permission == null ? "" : permission.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 内容没变、只有登记表元数据变了时走这里（G2）。
     *
     * <p>幂等跳过原本是"什么都不做"，于是改了 manifest 里的密级、标题、责任部门都永远不生效——
     * 因为判定幂等的依据是**文件内容**，而密级根本不来自文件。密级是这个系统里最依赖
     * "改了就得生效"的字段（它决定谁能看见），所以元数据与 ACL 必须在这里补一次。</p>
     *
     * <p>刻意不动 {@code domain_code}：知识域变了意味着内核里的工作区变了，只改本地行会让
     * 两边指向不同的地方，那是另一件事（要重新上传），不能在这里悄悄改。</p>
     *
     * @return 给人看的变更说明；没有变化返回空串
     */
    public String syncMetadata(Row existing, IngestManifest.Entry entry, String domainCode) {
        List<String> changes = new ArrayList<>();
        String title = blankTo(entry.title(), existing.title());
        String docStatus = blankTo(entry.docStatus(), "现行");
        String secrecy = blankTo(entry.secrecy(), "内部公开");
        String ownerDept = blankToNull(entry.ownerDept());
        if (!Objects.equals(title, existing.title())) {
            changes.add("标题 " + existing.title() + " → " + title);
        }
        if (!Objects.equals(docStatus, existing.docStatus())) {
            changes.add("状态 " + existing.docStatus() + " → " + docStatus);
        }
        if (!Objects.equals(secrecy, existing.secrecy())) {
            changes.add("密级 " + existing.secrecy() + " → " + secrecy);
        }
        if (!Objects.equals(ownerDept, existing.ownerDept())) {
            changes.add("责任部门 " + existing.ownerDept() + " → " + ownerDept);
        }
        if (!changes.isEmpty()) {
            jdbc.update("""
                    update document set title = ?, doc_status = ?, secrecy = ?, owner_dept = ?,
                           effective_from = cast(? as date), effective_to = cast(? as date), updated_at = now()
                     where id = ?
                    """, title, docStatus, secrecy, ownerDept,
                    blankToNull(entry.effectiveFrom()), blankToNull(entry.effectiveTo()), existing.id());
        }
        if (replaceAcl(existing.id(), entry.secrecy())) {
            changes.add("可见范围按密级重写（" + secrecy + "）");
        }
        return changes.isEmpty() ? "" : "元数据已同步：" + String.join("；", changes);
    }

    private record AclRule(String subjectType, String subjectValue) {
    }

    private static List<AclRule> aclRules(String secrecy) {
        String level = secrecy == null ? "" : secrecy.trim();
        if (level.contains("秘密") || level.contains("机密")) {
            return List.of(new AclRule("ROLE", "admin"), new AclRule("ROLE", "hr"));
        }
        return List.of(new AclRule("ALL", "*"));
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
