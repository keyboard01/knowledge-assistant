package io.github.keyboard01.assistant.retrieval;

import java.util.List;

/**
 * 检索结果。
 * {@code insufficient} 表示"证据不足以作答"，{@code hint} 是给人看的原因/降级提示——
 * 宁可显式说"没找到/没权限"，也不要静默给一个像是答对了的结果。
 *
 * <p>{@code hint} 与 {@code accessNotice} 的读者不同，所以是两句话：{@code hint} 面向排障
 * （这个知识域为什么答不上来，含"没凑满 topN"这类接口层面的信息），{@code accessNotice} 面向用户
 * （你看到的答案缺了证据）。混成一句，就会把"少于请求的 5 份"这种接口参数漏给用户。</p>
 */
public record RetrievalResult(List<Evidence> evidences, boolean insufficient, String hint, String accessNotice,
                              RetrievalDiagnostics diagnostics) {
}
