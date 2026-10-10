package io.github.keyboard01.assistant.view;

/**
 * 源文件超出在线查看/下载的体积上限（AC-D-009）。
 *
 * <p>水印要求"整份重写字节"，就没有 HTTP Range 分页这条路了——大文件必然整份进内存。
 * 与其在 OOM 之后才发现，不如在门口就明确拒绝：拒绝是可观测的策略，OOM 不是。</p>
 */
public class DocumentTooLargeException extends RuntimeException {

    public DocumentTooLargeException(String message) {
        super(message);
    }
}