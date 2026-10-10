package io.github.keyboard01.assistant.view;

/**
 * 无权查看该文档（AC-D-001）。
 *
 * <p><b>为什么不存在与无权限用同一个异常。</b>如果"文档不存在"返回 404、"无权"返回 403，
 * 那仅凭状态码就能枚举出"哪些编号是真实存在的文档"。两者必须同形同码，
 * 响应体也必须是同一句不含任何文档信息的话。</p>
 */
public class DocumentAccessDeniedException extends RuntimeException {

    public DocumentAccessDeniedException() {
        super("没有权限查看该文档");
    }
}