package io.github.keyboard01.assistant.parse;

import java.util.regex.Pattern;

/**
 * 标题判定。PDF 没有"标题"这个概念（只有一堆带坐标的字形），DOCX 也未必用样式，
 * 所以只能靠三个信号投票：编号、字号、加粗。
 *
 * <p>本项目的语料就是活样本：DOCX 里的"1 概述"既没有 pStyle 也不是 Heading 样式，
 * 只有加粗 + 14pt；PDF 里"4 费用标准"是 13pt 黑体，正文 9pt。纯按样式判会一条标题都认不出来。</p>
 */
public final class HeadingRules {

    /** 1 / 1.2 / 3.4.5 / 第一章 / 一、 这类中文文档的编号习惯。 */
    private static final Pattern NUMBERED =
            Pattern.compile("^(\\d+(\\.\\d+)*|第[一二三四五六七八九十百]+[章节条]|[一二三四五六七八九十]+、)\\s*\\S.*$");
    /** 列表项：·、•、-、* 或 1) 1、 开头。 */
    private static final Pattern BULLET =
            Pattern.compile("^[·•\\-*▪]|^\\d+[)）]\\s*");
    /** 图表题注。它们该留在正文里，不该当标题。 */
    private static final Pattern CAPTION = Pattern.compile("^(图|表)\\s*\\d+.*$");

    private HeadingRules() {
    }

    public static boolean isBullet(String text) {
        return text != null && BULLET.matcher(text.trim()).find();
    }

    public static boolean isCaption(String text) {
        return text != null && CAPTION.matcher(text.trim()).matches();
    }

    /**
     * @param bodySize 正文字号（该文档的中位字号）；字号不可得时给 0，此时只靠编号与加粗
     */
    public static boolean isHeading(String text, double fontSize, double bodySize, boolean bold) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 40 || isCaption(t)) {
            return false;
        }
        if (isBullet(t) || t.endsWith("。") || t.endsWith("；")) {
            return false;
        }
        boolean numbered = NUMBERED.matcher(t).matches();
        boolean bigger = bodySize > 0 && fontSize >= bodySize + 1.0;
        // 纯加粗 + 短句也算标题：中文文档里"责任部门：财务部"这类不该算，所以要求同时像编号或明显更大
        if (numbered && (bigger || bold || t.length() <= 20)) {
            return true;
        }
        return bigger && bold && t.length() <= 30;
    }

    /** 章节路径里只保留"几级 + 标题"，去掉首尾空白与全角空格。 */
    public static String clean(String text) {
        return text == null ? "" : text.replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }
}