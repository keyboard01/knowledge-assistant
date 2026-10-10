package io.github.keyboard01.assistant.retrieval;

/**
 * 本地登记表里的一份文档（只保留检索需要的字段）。
 * 命中片段只带得回内核文档名，反查成这个对象之后才能谈权限、版本与引用。
 */
public record DocumentRef(long id, String docNo, String title, String version, String docStatus,
                          String secrecy, String ownerDept, String kernelDocName, String indexStatus) {

    public boolean searchable() {
        return "INDEXED".equalsIgnoreCase(indexStatus);
    }
}
