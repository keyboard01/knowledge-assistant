package io.github.keyboard01.assistant.deep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.keyboard01.assistant.deep.DeepEvidenceMerger.Round;
import io.github.keyboard01.assistant.deep.DeepModels.Step;
import io.github.keyboard01.assistant.deep.DeepModels.SubQuestion;
import io.github.keyboard01.assistant.retrieval.Evidence;

/**
 * S6：工具调用循环。
 *
 * <p>循环的骨架是**确定性**的：每个子问题先各检索一次，再围绕"锚点"把一份文档的一段窗口补回来。
 * 之后才轮到模型自选动作。这么排的理由是 §14 的实验结论：塌掉的原因是"承载答案的表行排在第 7 名
 * 之后"，而模型会不会想到去读表行是不稳定的——机制上的问题要用机制修，不能赌模型。</p>
 *
 * <p>三条停止规则都在这里，而且都是**正常结束**：预算触顶、本轮没有新增证据、模型说够了。
 * 循环里不存在"死循环"这种状态，因为每转一圈都要么花掉一次预算额度，要么直接停。</p>
 */
public final class EvidenceExplorer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExplorer.class);

    /**
     * 补齐窗口一次读几段：9 = 锚点前 4 段 + 锚点 + 锚点后 4 段。
     *
     * <p>为什么是"围绕锚点的窗口"而不是"从锚点往后读"：承载答案的表行往往被切成一个个独立片段，
     * 命中的那一行完全可能排在锚点**之后**，锚点自己（乃至答案行）却排在前面——问"每 2000 小时
     * 保养什么"、命中的却是 4000h 那一行就是这种情况。窗口两个方向都读，这一刀才不会切偏。</p>
     */
    static final int EXPAND_FRAGMENTS = 9;

    /** 窗口往前铺几段。锚点前的兄弟行同样是答案：四档周期表里，锚点常常落在表格中段。 */
    static final int READ_BACK_FRAGMENTS = 4;

    /** 模型自选读文档却没写段数时的默认值：比补齐窗口小一点，它读的是自己认定的位置。 */
    static final int MODEL_READ_FRAGMENTS = 6;

    static final String STOP_DONE = "DONE";
    static final String STOP_MODEL_FINISH = "MODEL_FINISH";
    static final String STOP_PLANNER_UNUSABLE = "PLANNER_UNUSABLE";
    static final String STOP_REPEATED_QUERY = "REPEATED_QUERY";
    static final String STOP_REPEATED_READ = "REPEATED_READ";
    static final String STOP_NO_NEW_EVIDENCE = "NO_NEW_EVIDENCE";

    /** 前端要画"探索过程"，所以每一步都要能推出去。 */
    public interface StepListener {

        StepListener NONE = step -> {
        };

        void onStep(Step step);
    }

    /**
     * @param newFragmentsLastRound 最后一轮新带回的片段数：为 0 是 S7 判"查不动了"的判据
     * @param uncoveredSubQuestions 一条证据都没摸到的子问题数
     */
    public record Exploration(DeepEvidenceMerger.Merged merged, List<Step> steps, String stopReason,
                              String stopMessage, String hint, String accessNotice, int blockedHitCount,
                              int searchedCount, int uncoveredSubQuestions, int newFragmentsLastRound) {
    }

    private record Anchor(long documentId, int chunkIndex, String title) {
    }

    private final DeepTools tools;
    private final DeepToolPlanner planner;
    private final DeepLedger ledger;
    private final StepListener listener;

    public EvidenceExplorer(DeepTools tools, DeepToolPlanner planner, DeepLedger ledger, StepListener listener) {
        this.tools = tools;
        this.planner = planner;
        this.ledger = ledger;
        this.listener = listener == null ? StepListener.NONE : listener;
    }

    public Exploration explore(String question, DeepPlan.Plan plan) throws IOException, InterruptedException {
        List<Round> rounds = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        Set<String> seenFragments = new LinkedHashSet<>();
        Set<String> triedQueries = new LinkedHashSet<>();
        Set<String> readAnchors = new LinkedHashSet<>();
        Set<Long> expandedDocuments = new LinkedHashSet<>();
        String hint = null;
        String accessNotice = null;
        int blocked = 0;
        int searched = 0;
        int lastNew = 0;
        String stopReason = STOP_DONE;
        String stopMessage = null;
        String lastSubQuestionId = plan.subQuestions().get(0).id();

        // 第 1 轮（确定性）：每个子问题各检索一次，先把"各条线索各自的头部证据"铺开。
        ledger.startRound();
        for (SubQuestion sub : plan.subQuestions()) {
            Optional<DeepLedger.Stop> stop = ledger.stop();
            if (stop.isPresent()) {
                stopReason = stop.get().code();
                stopMessage = stop.get().message();
                break;
            }
            if (!triedQueries.add(DeepPlan.normalize(sub.question()))) {
                continue;
            }
            DeepTools.Outcome outcome = tools.search(sub.question());
            ledger.chargeTool();
            searched++;
            lastSubQuestionId = sub.id();
            int added = collect(seenFragments, rounds, List.of(sub.id()), outcome.evidences());
            lastNew = added;
            record(steps, "search", "检索子问题 " + sub.id() + "：" + sub.question(), sub.question(), null, null, added);
            blocked += outcome.blockedHitCount();
            hint = firstNonBlank(hint, outcome.hint());
            accessNotice = firstNonBlank(accessNotice, outcome.accessNotice());
        }

        // 第 2 轮（确定性）：围绕每个子问题最强文档里的"锚点"补一个窗口回来。
        // §14 根因 1 就修在这里：表行不会因为分数低而消失，它只是排在 6 名之后没被带回来。
        if (STOP_DONE.equals(stopReason) && ledger.roundsLeft()) {
            ledger.startRound();
            for (SubQuestion sub : plan.subQuestions()) {
                Optional<DeepLedger.Stop> stop = ledger.stop();
                if (stop.isPresent()) {
                    stopReason = stop.get().code();
                    stopMessage = stop.get().message();
                    break;
                }
                Optional<Anchor> anchor = anchor(rounds, sub.id());
                if (anchor.isEmpty()) {
                    continue;
                }
                Anchor position = anchor.get();
                // 一份文档只做一次补齐：读回来的表行是所有相关子问题共用的（见下面的 touching），
                // 每个子问题再各读一遍只是把同一批片段重复买一次，还会把片段上限挤爆。
                if (!expandedDocuments.add(position.documentId())) {
                    continue;
                }
                // 补齐的开关：锚点之前那几段还有没读过的、或者锚点之后还有内容，才值得读这一趟。
                // 只看"锚点之后还有没有"会漏掉锚点**之前**的兄弟行；只看"前面读没读过"又会在
                // 文档还有下文时提前收手，所以两边都要看。
                int from = Math.max(0, position.chunkIndex() - READ_BACK_FRAGMENTS);
                Set<Integer> known = knownIndexes(rounds, position.documentId());
                if (unseen(known, from, READ_BACK_FRAGMENTS) == 0
                        && !tools.hasMore(position.documentId(), position.chunkIndex())) {
                    continue;
                }
                DeepTools.Outcome outcome = tools.readDocument(position.documentId(), from, EXPAND_FRAGMENTS);
                ledger.chargeTool();
                // 读回来的上下文归**所有摸到过这份文档的子问题**共有：一份表的数据行，对"分几档"和
                // "最长一档做什么"这两问同样重要，只记在触发它的那一问上，另一问就会缺行。
                int added = collect(seenFragments, rounds, touching(rounds, plan, position.documentId(), sub.id()),
                        outcome.evidences());
                lastNew = added;
                record(steps, "read_document", "围绕第 " + position.chunkIndex() + " 段读《" + position.title()
                        + "》第 " + from + " 段起的 " + EXPAND_FRAGMENTS + " 段（子问题 " + sub.id() + " 的锚点）",
                        null, position.documentId(), from, added);
            }
        }

        // 第 3 轮起：模型自选动作，一轮一个。没有决策者（对照实验/单测）时整段跳过。
        while (planner != null && STOP_DONE.equals(stopReason) && ledger.roundsLeft()) {
            Optional<DeepLedger.Stop> stop = ledger.stop();
            if (stop.isPresent()) {
                stopReason = stop.get().code();
                stopMessage = stop.get().message();
                break;
            }
            String digest = digest(question, plan, rounds, triedQueries);
            Optional<ToolCall> next = planner.next(digest);
            ledger.startRound();
            if (next.isEmpty()) {
                stopReason = STOP_PLANNER_UNUSABLE;
                stopMessage = "模型没有给出可识别的下一步动作，按现有证据作答";
                break;
            }
            ToolCall call = next.get();
            if (call.action() == ToolCall.Action.FINISH) {
                stopReason = STOP_MODEL_FINISH;
                stopMessage = "模型判断证据已经足够";
                break;
            }
            int added;
            if (call.action() == ToolCall.Action.SEARCH) {
                if (!triedQueries.add(DeepPlan.normalize(call.question()))) {
                    stopReason = STOP_REPEATED_QUERY;
                    stopMessage = "模型重复了已经试过的检索词，停止探索";
                    break;
                }
                DeepTools.Outcome outcome = tools.search(call.question());
                ledger.chargeTool();
                searched++;
                // 模型换词查的是"还空着的那个子问题"的可能性最大：它不是替某一条查，而是替整体补漏。
                added = collect(seenFragments, rounds, List.of(weakest(plan, rounds)), outcome.evidences());
                record(steps, "search", "模型改问：" + call.question(), call.question(), null, null, added);
                blocked += outcome.blockedHitCount();
                hint = firstNonBlank(hint, outcome.hint());
                accessNotice = firstNonBlank(accessNotice, outcome.accessNotice());
            } else {
                int from = call.fromChunkIndex() == null ? 0 : Math.max(0, call.fromChunkIndex());
                if (!readAnchors.add(call.documentId() + ":" + from)) {
                    stopReason = STOP_REPEATED_READ;
                    stopMessage = "模型要求重复读同一段，停止探索";
                    break;
                }
                int limit = call.limit() == null ? MODEL_READ_FRAGMENTS : call.limit();
                DeepTools.Outcome outcome = tools.readDocument(call.documentId(), from, limit);
                ledger.chargeTool();
                added = collect(seenFragments, rounds, touching(rounds, plan, call.documentId(),
                        weakest(plan, rounds)), outcome.evidences());
                record(steps, "read_document", "模型要求读文档 #" + call.documentId() + " 第 " + from + " 段起",
                        null, call.documentId(), from, added);
            }
            lastNew = added;
            if (added == 0) {
                // "没有新增证据"是设计文档 §4.4 S7 明确点名的**合法结束状态**，不是失败。
                stopReason = STOP_NO_NEW_EVIDENCE;
                stopMessage = "本轮没有带回新的片段，视为证据已到边界";
                break;
            }
        }

        DeepEvidenceMerger.Merged merged = DeepEvidenceMerger.merge(rounds);
        int uncovered = (int) plan.subQuestions().stream().filter(sub -> !merged.covered(sub.id())).count();
        log.debug("探索结束 停止={} 子问题={} 证据={} 未覆盖={} 工具调用={} 轮数={}", stopReason,
                plan.subQuestions().size(), merged.evidences().size(), uncovered, ledger.toolCalls(),
                ledger.rounds());
        return new Exploration(merged, List.copyOf(steps), stopReason, stopMessage, hint, accessNotice, blocked,
                searched, uncovered, lastNew);
    }

    /**
     * 子问题 → 它最强的那份文档 + 已经在它里面读到的最远片段。锚点取"最远"，因为它标出了这一路
     * 检索在文档里探到的边界：第 2 轮就在它周围铺一个窗口，把两侧的兄弟片段一起兜回来。
     */
    private static Optional<Anchor> anchor(List<Round> rounds, String subQuestionId) {
        long bestDocument = -1;
        double bestScore = -1;
        String bestTitle = null;
        for (Round round : rounds) {
            if (!round.subQuestionId().equals(subQuestionId)) {
                continue;
            }
            for (Evidence evidence : round.evidences()) {
                if (evidence.bestScore() > bestScore) {
                    bestScore = evidence.bestScore();
                    bestDocument = evidence.documentId();
                    bestTitle = evidence.title();
                }
            }
        }
        if (bestDocument < 0) {
            return Optional.empty();
        }
        int furthest = -1;
        for (Round round : rounds) {
            if (!round.subQuestionId().equals(subQuestionId)) {
                continue;
            }
            for (Evidence evidence : round.evidences()) {
                if (evidence.documentId() != bestDocument) {
                    continue;
                }
                for (Evidence.Fragment fragment : evidence.fragments()) {
                    if (fragment.chunkIndex() != null) {
                        furthest = Math.max(furthest, fragment.chunkIndex());
                    }
                }
            }
        }
        if (furthest < 0) {
            return Optional.empty();
        }
        return Optional.of(new Anchor(bestDocument, furthest, bestTitle == null ? "未知文档" : bestTitle));
    }

    /** 这份文档已经被带回来的片段序号（跨子问题的并集）：第 2 轮靠它判断"窗口里还缺不缺东西"。 */
    private static Set<Integer> knownIndexes(List<Round> rounds, long documentId) {
        Set<Integer> known = new LinkedHashSet<>();
        for (Round round : rounds) {
            for (Evidence evidence : round.evidences()) {
                if (evidence.documentId() != documentId) {
                    continue;
                }
                for (Evidence.Fragment fragment : evidence.fragments()) {
                    if (fragment.chunkIndex() != null) {
                        known.add(fragment.chunkIndex());
                    }
                }
            }
        }
        return known;
    }

    /**
     * {@code [from, from + size)} 里还有几段没被带回来。
     *
     * <p>用"缺不缺"而不是"有没有内容"当判据的一个副作用是：序号区间本来可能有空洞（空片段不进库），
     * 空洞会被算成"要读"。这一侧宁可白读一次，也不要因为算漏而漏掉一行答案。</p>
     */
    private static int unseen(Set<Integer> known, int from, int size) {
        int unseen = 0;
        for (int index = from; index < from + size; index++) {
            if (!known.contains(index)) {
                unseen++;
            }
        }
        return unseen;
    }

    /** 已经摸到过这份文档的子问题（按计划顺序）；一个都没有时用 {@code fallback}。 */
    private static List<String> touching(List<Round> rounds, DeepPlan.Plan plan, long documentId, String fallback) {
        List<String> ids = new ArrayList<>();
        for (SubQuestion sub : plan.subQuestions()) {
            boolean hit = rounds.stream().anyMatch(round -> round.subQuestionId().equals(sub.id())
                    && round.evidences().stream().anyMatch(evidence -> evidence.documentId() == documentId));
            if (hit) {
                ids.add(sub.id());
            }
        }
        return ids.isEmpty() ? List.of(fallback) : ids;
    }

    /** 还没有任何证据的子问题；全都覆盖了就给最后一条（分步作答按子问题切片，归到哪一条都不能丢证据）。 */
    private static String weakest(DeepPlan.Plan plan, List<Round> rounds) {
        for (SubQuestion sub : plan.subQuestions()) {
            boolean covered = rounds.stream().anyMatch(round -> round.subQuestionId().equals(sub.id())
                    && !round.evidences().isEmpty());
            if (!covered) {
                return sub.id();
            }
        }
        return plan.subQuestions().get(plan.subQuestions().size() - 1).id();
    }

    private String digest(String question, DeepPlan.Plan plan, List<Round> rounds, Set<String> triedQueries) {
        StringBuilder text = new StringBuilder("【原问题】").append(question).append("\n【子问题与覆盖情况】\n");
        for (SubQuestion sub : plan.subQuestions()) {
            text.append("  ").append(sub.id()).append(' ').append(sub.question()).append(" —— ");
            Map<Long, Evidence> strongest = new LinkedHashMap<>();
            int fragments = 0;
            int furthest = -1;
            for (Round round : rounds) {
                if (!round.subQuestionId().equals(sub.id())) {
                    continue;
                }
                for (Evidence evidence : round.evidences()) {
                    fragments += evidence.fragments().size();
                    strongest.merge(evidence.documentId(), evidence,
                            (left, right) -> left.bestScore() >= right.bestScore() ? left : right);
                    for (Evidence.Fragment fragment : evidence.fragments()) {
                        if (fragment.chunkIndex() != null) {
                            furthest = Math.max(furthest, fragment.chunkIndex());
                        }
                    }
                }
            }
            if (fragments == 0) {
                text.append("尚无证据，建议换更具体的检索词\n");
                continue;
            }
            text.append("片段 ").append(fragments).append(" 条，涉及 ").append(strongest.size()).append(" 份文档；")
                    .append("最远读到片段序号 ").append(furthest < 0 ? "无（没有序号信息）" : furthest);
            int shown = 0;
            for (Evidence evidence : strongest.values()) {
                if (shown++ >= 2) {
                    break;
                }
                text.append("\n      《").append(evidence.title()).append("》(id=").append(evidence.documentId())
                        .append(", ").append(evidence.version()).append(") 最新分 ")
                        .append(String.format("%.3f", evidence.bestScore()));
            }
            text.append('\n');
        }
        text.append("【已试过的检索词】");
        text.append(triedQueries.isEmpty() ? "（无）" : String.join(" / ", triedQueries));
        text.append("\n【预算】工具调用 ").append(ledger.toolCalls()).append('/').append(ledger.budget().maxToolCalls())
                .append("，轮数 ").append(ledger.rounds()).append('/').append(ledger.budget().maxRounds())
                .append("，已用 ").append(ledger.elapsedMs() / 1000).append("s/")
                .append(ledger.budget().maxElapsedMs() / 1000).append("s。");
        return text.toString();
    }

    /** 收下一轮的证据，返回**新片段数**（按自然键去重后）：去重口径与合并阶段完全一致。 */
    private static int collect(Set<String> seenFragments, List<Round> rounds, List<String> subQuestionIds,
                               List<Evidence> found) {
        for (String subQuestionId : subQuestionIds) {
            rounds.add(new Round(subQuestionId, found));
        }
        int added = 0;
        for (Evidence evidence : found) {
            for (Evidence.Fragment fragment : evidence.fragments()) {
                if (seenFragments.add(DeepEvidenceMerger.key(fragment))) {
                    added++;
                }
            }
        }
        return added;
    }

    private void record(List<Step> steps, String action, String detail, String query, Long documentId,
                        Integer fromChunkIndex, int newFragments) {
        Step step = new Step(steps.size() + 1, action, detail, query, documentId, fromChunkIndex, newFragments,
                ledger.elapsedMs());
        steps.add(step);
        listener.onStep(step);
    }

    private static String firstNonBlank(String current, String candidate) {
        return current != null && !current.isBlank() ? current : candidate;
    }
}
