package io.github.keyboard01.assistant.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作区(知识域)级授权（domain_acl）。
 * 与 {@link DocumentAclRepository} 同构：这里只做存取，判定统一走 {@link AclRule#grantsAny}。
 * 管理端可替换整份授权（replace），fail-closed 语义由 {@code DomainService.canAccess} 保证。
 */
@Repository
public class DomainAclRepository {

    private final JdbcTemplate jdbc;

    public DomainAclRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, List<AclRule>> findByDomainCodes(Collection<String> domainCodes) {
        if (domainCodes == null || domainCodes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(domainCodes.size(), "?"));
        Map<String, List<AclRule>> byDomain = new LinkedHashMap<>();
        String sql = """
                select domain_code, subject_type, subject_value, permission
                  from domain_acl
                 where domain_code in (""" + placeholders + ")";
        RowCallbackHandler collect = rs -> byDomain
                .computeIfAbsent(rs.getString("domain_code"), key -> new ArrayList<>())
                .add(new AclRule(rs.getString("subject_type"), rs.getString("subject_value"),
                        rs.getString("permission")));
        jdbc.query(sql, collect, domainCodes.toArray());
        return byDomain;
    }

    public List<AclRule> findByDomainCode(String domainCode) {
        return jdbc.query("""
                select subject_type, subject_value, permission
                  from domain_acl
                 where domain_code = ?
                """,
                (rs, rowNum) -> new AclRule(rs.getString("subject_type"), rs.getString("subject_value"),
                        rs.getString("permission")),
                domainCode);
    }

    /** 整份替换某个域的授权。删旧 + 写新放在一个事务里，避免中途失败留下"半份授权"。 */
    @Transactional
    public void replace(String domainCode, List<AclRule> rules) {
        jdbc.update("delete from domain_acl where domain_code = ?", domainCode);
        if (rules == null) {
            return;
        }
        for (AclRule rule : rules) {
            jdbc.update("""
                    insert into domain_acl (domain_code, subject_type, subject_value, permission)
                    values (?, ?, ?, ?)
                    on conflict do nothing
                    """, domainCode, rule.subjectType(),
                    rule.subjectValue() == null ? "" : rule.subjectValue(),
                    rule.permission() == null || rule.permission().isBlank() ? AclRule.READ : rule.permission());
        }
    }
}
