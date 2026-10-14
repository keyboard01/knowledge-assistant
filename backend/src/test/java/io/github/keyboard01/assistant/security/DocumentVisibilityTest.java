package io.github.keyboard01.assistant.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「按状态可见吗」的口径：列表与查看共用一份（{@code DocumentRepository.findVisible} 的 SQL 对应物）。
 *
 * <p>这里坏起来的代价是"待审核列表里的查看按钮必然 403"——功能看着在、点下去才发现是死的。</p>
 */
class DocumentVisibilityTest {

    @Test
    @DisplayName("管理员：草案与已废止都能看（否则没法在列表里审核）")
    void adminSeesEveryStatus() {
        assertTrue(DocumentVisibility.visibleByStatus("草案·未生效", "alice", "admin", true));
        assertTrue(DocumentVisibility.visibleByStatus("已废止", "alice", "admin", true));
        assertTrue(DocumentVisibility.visibleByStatus("现行", "alice", "admin", true));
    }

    @Test
    @DisplayName("非管理员：现行可见，自己上传的草案可见，别人的草案不可见")
    void nonAdminSeesCurrentAndOwnDraft() {
        assertTrue(DocumentVisibility.visibleByStatus("现行", "bob", "alice", false));
        assertTrue(DocumentVisibility.visibleByStatus("草案·未生效", "Alice", "alice", false));
        assertFalse(DocumentVisibility.visibleByStatus("草案·未生效", "bob", "alice", false));
        assertFalse(DocumentVisibility.visibleByStatus("已废止", "bob", "alice", false));
    }

    @Test
    @DisplayName("状态为空按不可见处理，不因 null 放行")
    void nullStatusIsNotVisible() {
        assertFalse(DocumentVisibility.visibleByStatus(null, "bob", "alice", false));
        assertFalse(DocumentVisibility.visibleByStatus(null, null, "alice", false));
    }

    @Test
    @DisplayName("管理员角色判定大小写与空白不敏感")
    void adminRoleIsTrimmedAndCaseInsensitive() {
        assertTrue(DocumentVisibility.isAdminRole(" Admin "));
        assertTrue(DocumentVisibility.isAdminRole("ADMIN"));
        assertFalse(DocumentVisibility.isAdminRole("user"));
        assertFalse(DocumentVisibility.isAdminRole(null));
    }
}
