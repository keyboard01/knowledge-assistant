package io.github.keyboard01.assistant.security;

import java.util.Locale;

/**
 * 文档检索可见性：**只有"现行"文档参与检索**（草案·未生效 / 已废止不参与）。
 *
 * <p>一处定义、多处引用，避免"列表看得到、检索搜不到"的口径漂移：
 * 检索 SQL（三条 chunk 查询）、检索命中重塑（{@link DocumentRef} 那一类）、
 * 深度通道按 id 读、查看原文、内核自检探针，全部用这里同一份谓词。</p>
 *
 * <p>刻意不 gate {@code GET /api/documents}：列表要能让管理员看到待审草案，
 * 对非管理员则收窄为"现行 + 自己上传的草案"（见 {@code DocumentRepository.findVisible}）。</p>
 */
public final class DocumentVisibility {

    /** 现行状态字面值。doc_status 是自由文本，判定用"包含"而非全等，与既有 selfCheck 口径一致。 */
    public static final String CURRENT = "现行";

    private DocumentVisibility() {
    }

    public static boolean isCurrent(String docStatus) {
        return docStatus != null && docStatus.contains(CURRENT);
    }

    /** 可下推的 SQL 谓词（用字面量，不占位符，保持各查询的占位符配平）。 */
    public static String currentSql(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias.trim() + ".";
        return prefix + "doc_status like '%" + CURRENT + "%'";
    }

    public static boolean isDraft(String docStatus) {
        return docStatus != null && docStatus.contains("草案");
    }

    static boolean isAdminRole(String roleCode) {
        return roleCode != null && "admin".equals(roleCode.trim().toLowerCase(Locale.ROOT));
    }
}
