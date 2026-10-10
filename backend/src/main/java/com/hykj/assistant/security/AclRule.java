package com.hykj.assistant.security;

/**
 * 一条文档级授权行（对应 document_acl 的一行）。
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
}
