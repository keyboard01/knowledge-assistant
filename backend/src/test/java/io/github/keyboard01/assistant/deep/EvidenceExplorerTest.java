package io.github.keyboard01.assistant.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.deep.DeepModels.Step;
import io.github.keyboard01.assistant.deep.DeepModels.SubQuestion;
import io.github.keyboard01.assistant.retrieval.Evidence;

/**
 * S6 循环：去重、预算、退出、上下文补齐。
 *
 * <p>这些都不依赖模型，所以能用假工具与假决策者钉死——真调模型就只能看运气了。</p>
 */
class EvidenceExplorerTest {

    private static final DeepBudget BUDGET = new DeepBudget(4, 10, 3, 60_000L, 10_000);
    private static final String QUESTION = "GA-75 保养周期分几档，最长一档做什么";

    /** 表格那一段的表头在第 10 段：补齐窗口由此往前铺 4 段，也就是从第 6 段读起。 */
    private static final int ANCHOR = 10;
    private static final int WINDOW_FROM = ANCHOR - EvidenceExplorer.READ_BACK_FRAGMENTS;

    @Test
    @DisplayName("聚合类问题：逐条检索之后再围绕锚点补一个窗口，表行才进得了证据表（§14 根因 1 的修法）")
    void expandsAroundAnchor() throws Exception {
        FakeTools tools = new FakeTools()
                .answer("GA-75 保养周期分几档", DeepFixtures.list(header(ANCHOR, 0.70)))
                .answer("GA-75 最长一档保养做什么", DeepFixtures.list(header(ANCHOR, 0.62)))
                .read(WINDOW_FROM, DeepFixtures.list(windowRows(WINDOW_FROM)));

        EvidenceExplorer.Exploration exploration = explorer(tools, null, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档", "GA-75 最长一档保养做什么"));

        assertEquals(EvidenceExplorer.STOP_DONE, exploration.stopReason());
        assertEquals(2, tools.searched.size());
        assertEquals(List.of(WINDOW_FROM), tools.readFrom, "一份文档只补一次，窗口起点落在锚点之前");
        Step read = exploration.steps().get(2);
        assertEquals("read_document", read.action());
        assertEquals(WINDOW_FROM, read.fromChunkIndex());
        assertEquals(8, read.newFragments(), "窗口 9 段里，表头已经由检索带回来过");

        Evidence document = exploration.merged().evidences().get(0);
        assertEquals(8, document.documentId());
        assertEquals(9, document.fragments().size(), "保养部位标题 6~9 + 表头 10 + 四档表行 11~14");
        assertTrue(document.fragments().stream().anyMatch(fragment -> fragment.chunkIndex() == 14));
        // 两问都要拿到这批表行：只记在触发读取的那一问上，另一问的分步作答就会缺行
        for (String subId : List.of("Q1", "Q2")) {
            assertTrue(exploration.merged().slicesBySubQuestion().get(subId).stream()
                    .flatMap(evidence -> evidence.fragments().stream())
                    .anyMatch(fragment -> fragment.chunkIndex() == 14), subId + " 的切片里没有表行");
        }
    }

    @Test
    @DisplayName("命中的是表格中间那一行时，窗口要把它前面的兄弟行也带回来（P26 漏掉 2000h 行的根因）")
    void windowCoversRowsBeforeTheAnchor() throws Exception {
        // 检索只命中了 4000h 那一行（第 13 段），而问题问的是 2000h（第 12 段）：只往后读就会漏掉它
        int anchor = 13;
        FakeTools tools = new FakeTools()
                .answer("GA-75 每 2000 小时保养什么", DeepFixtures.list(DeepFixtures.document(8,
                        "QM-WX-GA75-2026", "GA-75 维修手册", 0.66,
                        DeepFixtures.fragment("chunk-" + anchor, anchor, text(anchor), 0.66))))
                .read(anchor - EvidenceExplorer.READ_BACK_FRAGMENTS,
                        DeepFixtures.list(windowRows(anchor - EvidenceExplorer.READ_BACK_FRAGMENTS)));

        EvidenceExplorer.Exploration exploration = explorer(tools, null, BUDGET)
                .explore(QUESTION, plan("GA-75 每 2000 小时保养什么"));

        Set<Integer> got = exploration.merged().evidences().get(0).fragments().stream()
                .map(Evidence.Fragment::chunkIndex).collect(Collectors.toSet());
        assertTrue(got.containsAll(List.of(11, 12, 13, 14)), "四档表行都要在证据里，实际：" + got);
        assertEquals(List.of(anchor - EvidenceExplorer.READ_BACK_FRAGMENTS), tools.readFrom);
    }

    @Test
    @DisplayName("资料已经读到末尾、锚点前那几段也读过了：补齐整步跳过，不花这次预算")
    void skipsExpansionWhenNothingLeft() throws Exception {
        FakeTools tools = new FakeTools().answer("GA-75 保养周期分几档", DeepFixtures.list(windowRows(14)))
                .noMore();

        EvidenceExplorer.Exploration exploration = explorer(tools, null, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档"));

        assertEquals(EvidenceExplorer.STOP_DONE, exploration.stopReason());
        assertEquals(List.of(), tools.readFrom);
        assertEquals(1, exploration.steps().size());
    }

    @Test
    @DisplayName("模型换词查到的还是同一批片段：判为无新增证据并停下（合法结束状态）")
    void stopsWhenNoNewEvidence() throws Exception {
        FakePlanner planner = new FakePlanner().then(Optional.of(ToolCall.search("更换周期表")));
        FakeTools tools = new FakeTools()
                .answer("GA-75 保养周期分几档", DeepFixtures.list(header(ANCHOR, 0.70)))
                .answer("更换周期表", DeepFixtures.list(header(ANCHOR, 0.70)));

        EvidenceExplorer.Exploration exploration = explorer(tools, planner, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档"));

        assertEquals(EvidenceExplorer.STOP_NO_NEW_EVIDENCE, exploration.stopReason());
        assertEquals(0, exploration.newFragmentsLastRound());
    }

    @Test
    @DisplayName("工具预算封顶：多出来的子问题不再查，调用次数不超过上限")
    void respectsToolBudget() throws Exception {
        FakeTools tools = new FakeTools().answer("Q-A", DeepFixtures.list(header(ANCHOR, 0.70)));

        EvidenceExplorer.Exploration exploration = explorer(tools, null, new DeepBudget(4, 1, 3, 60_000L, 10_000))
                .explore(QUESTION, plan("Q-A", "Q-B", "Q-C"));

        assertEquals(DeepLedger.CODE_TOOL_BUDGET, exploration.stopReason());
        assertEquals(1, tools.searched.size());
        assertEquals(1, exploration.steps().size());
    }

    @Test
    @DisplayName("重复查询不再执行：同一句话查两遍，第二遍只会把预算花掉")
    void rejectsRepeatedQuery() throws Exception {
        FakePlanner planner = new FakePlanner().then(Optional.of(ToolCall.search("GA-75 保养周期，分几档？")));
        FakeTools tools = new FakeTools().answer("GA-75 保养周期分几档", DeepFixtures.list(header(ANCHOR, 0.70)));

        EvidenceExplorer.Exploration exploration = explorer(tools, planner, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档"));

        assertEquals(EvidenceExplorer.STOP_REPEATED_QUERY, exploration.stopReason());
        assertEquals(1, tools.searched.size());
    }

    @Test
    @DisplayName("模型说够了就停，不多查一轮")
    void stopsOnModelFinish() throws Exception {
        FakePlanner planner = new FakePlanner().then(Optional.of(ToolCall.finish()));
        FakeTools tools = new FakeTools()
                .answer("GA-75 保养周期分几档", DeepFixtures.list(windowRows(14)))
                .answer("GA-75 最长一档保养做什么", DeepFixtures.list(windowRows(14)))
                .noMore();

        EvidenceExplorer.Exploration exploration = explorer(tools, planner, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档", "GA-75 最长一档保养做什么"));

        assertEquals(EvidenceExplorer.STOP_MODEL_FINISH, exploration.stopReason());
        assertEquals(0, tools.readFrom.size());
    }

    @Test
    @DisplayName("一条证据都没摸到的子问题要单独计数：S7 靠它决定'半答'还是'全答'")
    void countsUncoveredSubQuestions() throws Exception {
        FakeTools tools = new FakeTools().answer("GA-75 保养周期分几档", DeepFixtures.list(header(ANCHOR, 0.70)));

        EvidenceExplorer.Exploration exploration = explorer(tools, null, BUDGET)
                .explore(QUESTION, plan("GA-75 保养周期分几档", "完全不存在的型号 XX-99 保养什么"));

        assertEquals(1, exploration.uncoveredSubQuestions());
        assertFalse(exploration.merged().covered("Q2"));
        assertTrue(exploration.merged().covered("Q1"));
    }

    private static Evidence header(int chunkIndex, double score) {
        return DeepFixtures.document(8, "QM-WX-GA75-2026", "GA-75 维修手册", score,
                DeepFixtures.fragment("chunk-" + chunkIndex, chunkIndex, text(chunkIndex), score));
    }

    /**
     * 假工具按 {@code from} 返回"这一窗里真实存在的片段"：doc 8 的第 6~9 段是保养部位标题、
     * 第 10 段是表头、第 11~14 段是四档表行。窗比文档长时按到末尾为止。
     */
    private static Evidence windowRows(int from) {
        List<Evidence.Fragment> fragments = new ArrayList<>();
        for (int index = from; index < from + EvidenceExplorer.EXPAND_FRAGMENTS; index++) {
            fragments.add(DeepFixtures.fragment("chunk-" + index, index, text(index), 0d));
        }
        return DeepFixtures.document(8, "QM-WX-GA75-2026", "GA-75 维修手册", 0d,
                fragments.toArray(new Evidence.Fragment[0]));
    }

    private static String text(int chunkIndex) {
        return switch (chunkIndex) {
            case 4 -> "1 空气滤清器";
            case 5 -> "2 油气分离器";
            case 6 -> "3 主机 / 皮带";
            case 7 -> "4 冷却器";
            case 8 -> "5 油过滤器";
            case 9 -> "6 排水阀";
            case 10 -> "周期保养项目部位编号";
            case 11 -> "每 500 h 检查空气滤清器指示器；排放储气罐冷凝水";
            case 12 -> "每 2000 h 更换空气滤清器滤芯；更换油过滤器";
            case 13 -> "每 4000 h 更换油气分离器；检查皮带张紧度与磨损";
            case 14 -> "每 8000 h 更换润滑油；清洗冷却器；检查主机轴承";
            default -> "保养作业步骤 " + chunkIndex;
        };
    }

    private static EvidenceExplorer explorer(DeepTools tools, DeepToolPlanner planner, DeepBudget budget) {
        return new EvidenceExplorer(tools, planner, new DeepLedger(budget), EvidenceExplorer.StepListener.NONE);
    }

    private static DeepPlan.Plan plan(String... questions) {
        List<SubQuestion> subQuestions = new ArrayList<>();
        for (int i = 0; i < questions.length; i++) {
            subQuestions.add(new SubQuestion("Q" + (i + 1), questions[i], "测试"));
        }
        return new DeepPlan.Plan(false, null, subQuestions);
    }

    private static final class FakeTools implements DeepTools {

        private final Map<String, List<Evidence>> byQuery = new LinkedHashMap<>();
        private final Map<Integer, List<Evidence>> byFrom = new LinkedHashMap<>();
        private final List<String> searched = new ArrayList<>();
        private final List<Integer> readFrom = new ArrayList<>();
        private boolean more = true;

        FakeTools answer(String question, List<Evidence> evidences) {
            byQuery.put(question, evidences);
            return this;
        }

        FakeTools read(int fromChunkIndex, List<Evidence> evidences) {
            byFrom.put(fromChunkIndex, evidences);
            return this;
        }

        FakeTools noMore() {
            more = false;
            return this;
        }

        @Override
        public Outcome search(String question) {
            searched.add(question);
            return new Outcome(byQuery.getOrDefault(question, List.of()), 0, null, null);
        }

        @Override
        public Outcome readDocument(long documentId, Integer fromChunkIndex, int limit) {
            int from = fromChunkIndex == null ? 0 : fromChunkIndex;
            readFrom.add(from);
            return new Outcome(byFrom.getOrDefault(from, List.of()), 0, null, null);
        }

        @Override
        public boolean hasMore(long documentId, int afterChunkIndex) {
            return more;
        }
    }

    private static final class FakePlanner implements DeepToolPlanner {

        private final List<Optional<ToolCall>> answers = new ArrayList<>();
        private int index;

        FakePlanner then(Optional<ToolCall> call) {
            answers.add(call);
            return this;
        }

        @Override
        public Optional<ToolCall> next(String digest) {
            return index < answers.size() ? answers.get(index++) : Optional.empty();
        }
    }
}
