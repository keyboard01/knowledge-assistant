package io.github.keyboard01.assistant.security;

import java.util.List;

/**
 * 一条授权行（对应 document_acl / domain_acl 的一行）。
 * 语义是白名单：命中一条才算可见，一条都没命中就是不可见（fail-closed）。
 */
public record AclRule(String subjectType, String subjectValue, String permission) {

    public static final String READ = "READ";

    public boolean grants(AccessSubject subject) {
        if (permission != null && !permission.isBlank() && !READ.equalsIgnoreCase(permission.trim())) {
            return false;
        }
        String type = subjectType == null ? "" : subjectType.trim().toUpperCase();
        String value = subjectValue == null ? "" : subjectValue.trim();
        return switch (type) {
            case "ALL" -> value.isEmpty() || "*".equals(value);
            case "ROLE" -> subject.roles().contains(value);
            case "USER" -> !subject.username().isEmpty() && subject.username().equals(value);
            case "DEPT" -> !subject.deptCode().isEmpty() && subject.deptCode().equals(value);
            default -> false;
        };
    }

    /**
     * 内存判定：命中任意一条即放行；空集合一律拒绝。
     * 文档级与域级共用这一份实现（{@link AccessFilter#allows} 与域授权都走它），避免两处语义漂移。
     */
    public static boolean grantsAny(AccessSubject subject, List<AclRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        for (AclRule rule : rules) {
            if (rule != null && rule.grants(subject)) {
                return true;
            }
        }
        return false;
    }
}
