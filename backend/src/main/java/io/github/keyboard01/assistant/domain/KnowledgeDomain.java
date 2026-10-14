package io.github.keyboard01.assistant.domain;

/**
 * 知识域。
 *
 * <p>{@code tenantCode} 是它所属的租户（一个项目一套）：可见性的作用域上界。
 * {@code createdBy} 是创建者用户名，只用于审计与"能管自己建的库"；升级前就存在的域为 null。</p>
 */
public record KnowledgeDomain(String code, String name, String description, boolean enabled,
                              String tenantCode, String createdBy) {
}
