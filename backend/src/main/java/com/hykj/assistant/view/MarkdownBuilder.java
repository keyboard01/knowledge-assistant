package com.hykj.assistant.view;

import java.util.List;
import java.util.Locale;

import com.hykj.assistant.view.DocumentViewModels.Block;

/**
 * 把片段拼成 Markdown（§13.1 的 D1 选 (a)：现场拼，不落 .md 文件）。
 *
 * <p>三条刻意的规则：</p>
 * <ul>
 *   <li><b>不猜表格结构。</b>表格行在入库时已经是"表头 ｜ 单元格 —— 单元格"这样的单行文本。
 *       按 {@code ｜} 与 {@code ——} 反推成 Markdown 表格看着聪明，猜错就会把正文排乱；
 *       原样成行至少是可读的（与 §10.9 "宁可让用户看到符号"同一条取向）。</li>
 *   <li><b>章节标题读 {@code block.sectionHead()}。</b>该由服务层按 {@code section_path} 的跳变算好
 *       （并排除"与文档同名的小节"），这里不再自己推一遍——同一份文档在 Markdown、PDF 与界面里
 *       必须是同一套章节结构。</li>
 * </ul>
 */
public final class MarkdownBuilder {

    private MarkdownBuilder() {
    }

    public static String build(String title, List<Block> blocks) {
        StringBuilder markdown = new StringBuilder();
        String docTitle = title == null ? "" : title.trim();
        if (!docTitle.isEmpty()) {
            markdown.append("# ").append(docTitle).append("\n\n");
        }
        for (Block block : blocks) {
            String text = block.text() == null ? "" : block.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (block.sectionHead()) {
                markdown.append("## ").append(block.sectionPath().trim()).append("\n\n");
            }
            switch (block.kind() == null ? "" : block.kind().toUpperCase(Locale.ROOT)) {
                // 文档标题已经占了 h1，正文里的标题整体下沉一级
                case "HEADING" -> markdown.append("#".repeat(clamp(block.level(), 1, 5) + 1))
                        .append(' ').append(text).append("\n\n");
                case "BULLET" -> markdown.append("- ").append(text).append('\n');
                default -> markdown.append(text).append("\n\n");
            }
        }
        return markdown.toString().stripTrailing() + "\n";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
