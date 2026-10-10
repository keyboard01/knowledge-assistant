package com.hykj.assistant.parse;

/**
 * 解析出来的一块内容。块是"语义单元"这一层：一个标题、一段正文、一条列表项、表格的一行。
 *
 * <p>为什么不在解析阶段就切 chunk：块是解析器的产物（PDF/DOCX 各自能认出的最小有意义单位），
 * 切分粒度是检索策略（P2 要反复调）。两层分开，调粒度不用重写解析器，也不用重跑解析。</p>
 *
 * <p>{@code page} 为 1 起的真实页码；PDF 有页码语义，DOCX 没有（Word 的分页是渲染结果，
 * 不是文档属性），所以 DOCX 一律给 0，表示"没有页码"，由上层映射成 null——不许瞎填。</p>
 */
public record ParsedBlock(Kind kind, String text, int page, int level) {

    public enum Kind {
        /** 标题（编号、字号或加粗认定） */
        HEADING,
        /** 正文段落 */
        PARAGRAPH,
        /** 列表项（·、•、-、数字编号开头的短行） */
        BULLET,
        /** 表格的一行 */
        TABLE_ROW
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}