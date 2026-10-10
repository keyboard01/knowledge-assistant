package com.hykj.assistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 标题判定只有三个信号（编号、字号、加粗），本项目语料就是活样本：
 * DOCX 的"1 概述"只有加粗 + 14pt，PDF 的"4 费用标准"是 13pt 黑体（正文 9pt）。
 * 纯按样式判一条标题都认不出来，所以这里把每个信号单独钉住。
 */
class HeadingRulesTest {

    @Test
    @DisplayName("编号 + 短句：即使字号不突出也算标题")
    void numberedShortLineIsHeading() {
        assertTrue(HeadingRules.isHeading("4 费用标准", 9, 9, false));
        assertTrue(HeadingRules.isHeading("3.4.1 报销流程", 9, 9, false));
        assertTrue(HeadingRules.isHeading("第一章 总则", 9, 9, false));
    }

    @Test
    @DisplayName("以句号/分号收尾或含正文标点的定义句不算标题")
    void sentenceLikeTextIsNotHeading() {
        assertFalse(HeadingRules.isHeading("注 2：一线城市指北京、上海、广州、深圳。", 9, 9, false));
        assertFalse(HeadingRules.isHeading("1. 本办法自发布之日起施行；", 9, 9, false));
    }

    @Test
    @DisplayName("图表题注不算标题：它该留在正文里")
    void captionIsNotHeading() {
        assertFalse(HeadingRules.isHeading("图 1 液压站原理图", 14, 9, true));
        assertFalse(HeadingRules.isHeading("表 2 维护周期", 14, 9, true));
    }

    @Test
    @DisplayName("列表项不算标题")
    void bulletIsNotHeading() {
        assertFalse(HeadingRules.isHeading("- 更换回油滤芯", 9, 9, false));
        assertTrue(HeadingRules.isBullet("1) 松开溢流阀"));
        assertTrue(HeadingRules.isBullet("· 检查油位"));
        assertFalse(HeadingRules.isBullet("1 概述"));
    }

    @Test
    @DisplayName("超长行一律不是标题")
    void longLineIsNeverHeading() {
        String longText = "本条用于说明报销单据的填写要求与审批流转顺序，涉及申请人、部门负责人、"
                + "分管副总与财务部四个环节，任何环节缺一不可。";
        assertFalse(HeadingRules.isHeading(longText, 14, 9, true));
    }

    @Test
    @DisplayName("加粗 + 字号明显更大 + 短句：无编号也能认定为标题")
    void boldAndBiggerShortLineIsHeading() {
        assertTrue(HeadingRules.isHeading("审批权限", 14, 9, true));
    }

    @Test
    @DisplayName("只是加粗、字号没变大、又没编号，不能算标题（如\"责任部门：财务部\"）")
    void boldAloneIsNotEnough() {
        assertFalse(HeadingRules.isHeading("责任部门：财务部", 9, 9, true));
    }

    @Test
    @DisplayName("clean 归一化全角空格与多余空白")
    void cleanNormalizesWhitespace() {
        assertEquals("第一章 总则", HeadingRules.clean("第一章\u3000总则"));
        assertEquals("a b", HeadingRules.clean("  a   b "));
        assertEquals("", HeadingRules.clean(null));
    }
}