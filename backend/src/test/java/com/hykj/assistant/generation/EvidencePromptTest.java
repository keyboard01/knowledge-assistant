package com.hykj.assistant.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hykj.assistant.retrieval.Evidence;

/**
 * 提示词是纯函数，所以能钉住"模型到底看到了什么"。答错时先看这里：
 * 是证据没给全，还是规则没写清，比盯着日志猜模型的心思靠谱得多。
 */
class EvidencePromptTest {

    @Test
    @DisplayName("系统提示钉住底线：只用证据、必须标引用、不得编造编号、版本冲突要并列")
    void systemPromptPinsTheNonNegotiables() {
        assertTrue(EvidencePrompt.SYSTEM.contains("不允许使用任何外部知识"));
        assertTrue(EvidencePrompt.SYSTEM.contains("[1]"));
        assertTrue(EvidencePrompt.SYSTEM.contains("不得编造编号"));
        assertTrue(EvidencePrompt.SYSTEM.contains("并列说明"));
    }

    @Test
    @DisplayName("证据按编号列出：标题、编号、版本、责任部门与片段正文都在，问题在最后")
    void userPromptCarriesEveryEvidence() {
        String prompt = EvidencePrompt.user("出差住宿费标准是多少",
                List.of(evidence(1, "QM-ZD-2026-003", "V3.2", "住宿费按城市分档"),
                        evidence(2, "QM-ZD-2026-011", "V1.4", "维修作业须断电挂牌")));

        assertTrue(prompt.contains("[1] 《QM-ZD-2026-003 的标题》"), prompt);
        assertTrue(prompt.contains("编号 QM-ZD-2026-003"));
        assertTrue(prompt.contains("版本 V3.2"));
        assertTrue(prompt.contains("责任部门 财务部"));
        assertTrue(prompt.contains("住宿费按城市分档"));
        assertTrue(prompt.contains("[2] 《QM-ZD-2026-011 的标题》"));
        assertTrue(prompt.contains("维修作业须断电挂牌"));
        assertTrue(prompt.contains("【证据】"));
        assertTrue(prompt.contains("【问题】"));
        assertTrue(prompt.endsWith("出差住宿费标准是多少"), "问题要在最后，模型才不会把问题当成一段证据");
    }

    @Test
    @DisplayName("同一份文档的多个命中片段全部进提示词（P1 章节展开的落点）")
    void includesAllFragmentsOfAnEvidence() {
        Evidence merged = new Evidence(1, 1L, "A-1", "A-1 的标题", "V1.0", "现行", "内部公开", "财务部",
                "A-1_V1.0.pdf", 0.9, List.of(
                        new Evidence.Fragment("c-1", "第一段", 0.9, 0.1, 10),
                        new Evidence.Fragment("c-2", "第二段", 0.7, 0.3, 10)));

        String prompt = EvidencePrompt.user("问题", List.of(merged));

        assertTrue(prompt.contains("第一段"));
        assertTrue(prompt.contains("第二段"));
    }

    @Test
    @DisplayName("片段正文里的换行被压平：否则证据段落会被正文撑散")
    void flattensNewlinesInsideFragmentText() {
        String twoLines = "第一行" + System.lineSeparator() + "第二行";

        String prompt = EvidencePrompt.user("问题", List.of(evidence(1, "A-1", "V1.0", twoLines)));

        assertTrue(prompt.contains("第一行 第二行"), prompt);
        assertFalse(prompt.contains(twoLines));
    }

    private static Evidence evidence(int rank, String docNo, String version, String fragmentText) {
        return new Evidence(rank, rank, docNo, docNo + " 的标题", version, "现行", "内部公开", "财务部",
                docNo + "_" + version + ".pdf", 0.8,
                List.of(new Evidence.Fragment("chunk-" + rank, fragmentText, 0.8, 0.2, 12)));
    }
}
