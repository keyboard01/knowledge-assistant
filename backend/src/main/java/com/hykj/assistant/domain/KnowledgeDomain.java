package com.hykj.assistant.domain;

public record KnowledgeDomain(String code, String name, String workspaceSlug, String description,
                              boolean enabled) {
}
