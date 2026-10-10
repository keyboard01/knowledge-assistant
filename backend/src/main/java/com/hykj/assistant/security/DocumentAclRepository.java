package com.hykj.assistant.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * ACL 的只读入口。写入在同步流水线里（按密级生成最小规则），判定与过滤条件生成在 {@link AccessFilter}；
 * 这里只负责"一次把需要的授权行取回来"，避免逐条查询。
 */
@Repository
public class DocumentAclRepository {

    private final JdbcTemplate jdbc;

    public DocumentAclRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, List<AclRule>> findByDocumentIds(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(documentIds.size(), "?"));
        Map<Long, List<AclRule>> byDocument = new LinkedHashMap<>();
        String sql = """
                select document_id, subject_type, subject_value, permission
                  from document_acl
                 where document_id in (""" + placeholders + ")";
        // 显式声明成 RowCallbackHandler：否则 lambda 会在它的两个重载之间产生歧义
        RowCallbackHandler collect = rs -> byDocument
                .computeIfAbsent(rs.getLong("document_id"), key -> new ArrayList<>())
                .add(new AclRule(rs.getString("subject_type"), rs.getString("subject_value"),
                        rs.getString("permission")));
        jdbc.query(sql, collect, documentIds.toArray());
        return byDocument;
    }
}
