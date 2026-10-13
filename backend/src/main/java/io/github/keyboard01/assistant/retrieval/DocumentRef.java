package io.github.keyboard01.assistant.retrieval;

import io.github.keyboard01.assistant.security.DocumentVisibility;

/**
 * 本地登记表里的一份文档（只保留检索需要的字段）。
 * 检索命中天然带着 {@code document_id}，反查成这个对象之后才能谈权限、版本与引用。
 */
public record DocumentRef(long id, String docNo, String title, String version, String docStatus,
                          String secrecy, String ownerDept, String chunkStatus) {

    /** 可检索 = 自建片段就绪 **且** 现行。与三条 chunk 查询的 SQL 谓词同一口径。 */
    public boolean searchable() {
        return "INDEXED".equalsIgnoreCase(chunkStatus) && DocumentVisibility.isCurrent(docStatus);
    }
}
