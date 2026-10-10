package com.hykj.assistant.deep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hykj.assistant.deep.DeepModels.Section;
import com.hykj.assistant.deep.DeepModels.SubQuestion;
import com.hykj.assistant.generation.AnswerComposer;
import com.hykj.assistant.generation.GeneratedAnswer;
import com.hykj.assistant.generation.LanguageModel;
import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalDiagnostics;
import com.hykj.assistant.retrieval.RetrievalResult;

/**
 * S8：分步作答 + 逐段引用校验。
 *
 * <p>为什么按子问题分段，而不是"一次生成整段、截断了再拆"：截断是**事后**才知道的，那时已经花了钱、
 * 也已经把用户的等待时间赔进去了。子问题本来就是天然的段落边界，按它切开，每段都短到不容易被截断，
 * 而且"哪一部分答不上来"可以直接标在那一部分上——这正是 §14 对 P30 的要求：
 * 不要因为一处并列证据太长就整段拒答。</p>
 *
 * <p>引用校验一个字都没放松：每一段仍然要过 {@link AnswerComposer}——证据不足、被截断、引用越界、
 * 一条引用都没有，四种情况照旧不允许当结论给用户看。</p>
 */
@Component
public class StepwiseAnswerer {

    private static final Logger log = LoggerFactory.getLogger(StepwiseAnswerer.class);

    /** 一段被截断时的重试指令：不换证据，只要求它收敛篇幅。 */
    static final String RETRY_SUFFIX = """

            补充要求：上一条回答因为太长被截断了，用户什么都没拿到。这一次只输出最关键的结论要点，
            控制在 150 字以内，不要复述证据原文，不要重复问题。
            """;

    /** 这一段没生成，原因不在模型也不在证据，而在预算（见 §4.6：预算触顶是正常结束状态）。 */
    static final String RULE_BUDGET_STOP = "BUDGET_STOP";

    /** 分段结果 + 汇总引用 + 生成侧用量。 */
    public record Result(List<Section> sections, List<GeneratedAnswer.Citation> citations, int answeredSections,
                         int modelCalls, String joined) {
    }

    private final LanguageModel languageModel;

    public StepwiseAnswerer(LanguageModel languageModel) {
        this.languageModel = languageModel;
    }

    public Result answer(DeepPlan.Plan plan, DeepEvidenceMerger.Merged merged, DeepLedger ledger,
                         Consumer<String> onDelta, Consumer<Section> onSection)
            throws IOException, InterruptedException {
        Map<String, List<Evidence>> slices = merged.slicesBySubQuestion();
        boolean anySlice = slices.values().stream().anyMatch(list -> !list.isEmpty());
        List<Section> sections = new ArrayList<>();
        Map<Integer, GeneratedAnswer.Citation> citations = new TreeMap<>();
        int answered = 0;
        int modelCalls = 0;
        int index = 0;
        for (SubQuestion sub : plan.subQuestions()) {
            index++;
            // 切片为空时退回整表：宁可能啰嗦一点，也不要因为"分工没对上"让某一问什么都不答。
            List<Evidence> slice = anySlice ? slices.getOrDefault(sub.id(), List.of()) : merged.evidences();
            if (slice.isEmpty()) {
                sections.add(new Section(index, sub.question(),
                        "本段证据不足：知识库里没有检索到能回答这一部分的片段。", List.of(), false,
                        AnswerComposer.RULE_INSUFFICIENT));
                continue;
            }
            Optional<DeepLedger.Stop> stop = ledger.stopForGeneration();
            if (stop.isPresent()) {
                sections.add(new Section(index, sub.question(), "本段未生成：" + stop.get().message(), List.of(),
                        false, RULE_BUDGET_STOP));
                continue;
            }
            GeneratedAnswer generated = generate(sub.question(), slice, merged.evidences(), ledger, false, onDelta);
            modelCalls++;
            if (generated.refused() && AnswerComposer.RULE_TRUNCATED.equals(generated.diagnostics().refusalRule())
                    && ledger.stopForGeneration().isEmpty()) {
                // 只有"被长度截断"值得重试一次：那是篇幅没收敛，不是证据的问题。重试前重新问一次预算
                // ——这一段已经花过一次 token 了。
                log.debug("分步作答第 {} 段被截断，改用更短的篇幅重试一次", index);
                if (onDelta != null) {
                    onDelta.accept("\n");
                }
                generated = generate(sub.question(), slice, merged.evidences(), ledger, true, onDelta);
                modelCalls++;
            }
            boolean ok = !generated.refused();
            if (ok) {
                answered++;
            }
            for (GeneratedAnswer.Citation citation : generated.citations()) {
                citations.putIfAbsent(citation.rank(), citation);
            }
            Section section = new Section(index, sub.question(), generated.answer(),
                    generated.citations().stream().map(GeneratedAnswer.Citation::rank).toList(), ok,
                    ok ? null : generated.diagnostics().refusalRule());
            sections.add(section);
            if (onSection != null) {
                onSection.accept(section);
            }
        }
        return new Result(List.copyOf(sections), List.copyOf(citations.values()), answered, modelCalls,
                join(sections));
    }

    /**
     * 调一次模型并过一遍引用校验。
     *
     * <p>{@code allEvidences} 传的是**整表**，而提示词只贴 {@code slice}。原因是引用校验按
     * "编号 → 证据"翻译，而切片里的编号是全局编号：只把切片交给校验器，编号范围会被误判成越界，
     * 于是模型写 [5] 这种完全合法的引用反而被拦下。代价是校验会放过"引用了本次运行里存在、
     * 但没贴给这一段"的编号——那仍然是一份真实的、已授权的证据，比误杀一整段要轻。</p>
     */
    private GeneratedAnswer generate(String subQuestion, List<Evidence> slice, List<Evidence> allEvidences,
                                     DeepLedger ledger, boolean retry, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        String system = retry ? DeepAnswerPrompt.SYSTEM + RETRY_SUFFIX : DeepAnswerPrompt.SYSTEM;
        String user = DeepAnswerPrompt.user(subQuestion, slice);
        long startedAt = System.nanoTime();
        LanguageModel.Completion completion = onDelta == null
                ? languageModel.complete(system, user)
                : languageModel.streamComplete(system, user, onDelta);
        ledger.chargeTokens(completion.promptTokens(), completion.completionTokens());
        ledger.noteModel(completion.model());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        RetrievalResult envelope = new RetrievalResult(allEvidences, false, null, null,
                envelopeDiagnostics(allEvidences));
        return AnswerComposer.compose(envelope, completion.content(),
                new AnswerComposer.Meta(completion.model(), true, completion.truncated(),
                        completion.promptTokens(), completion.completionTokens(), elapsedMs));
    }

    /**
     * 包一个"证据足够"的检索结果，好让 {@link AnswerComposer} 只做生成侧的四项判定。
     * S7 已经判过"证据够不够"，这里再判一次等于用两套阈值说同一件事。
     */
    private static RetrievalDiagnostics envelopeDiagnostics(List<Evidence> allEvidences) {
        double best = allEvidences.stream().mapToDouble(Evidence::bestScore).max().orElse(0d);
        int count = allEvidences.size();
        return new RetrievalDiagnostics("deep", count, 0d, 0d, 0d, 0, count, 0, 0, 0d, 0, 0, 0, 0, count, count,
                best, false, 0L, false, 0d);
    }

    /** 拼成给用户看的整段答案。只有多段时才加小标题——单段加个"①"徒增噪音。 */
    private static String join(List<Section> sections) {
        boolean numbered = sections.size() > 1;
        StringBuilder text = new StringBuilder();
        for (Section section : sections) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            if (numbered) {
                text.append(marker(section.index())).append(' ').append(section.question()).append('\n');
            }
            text.append(section.text() == null ? "" : section.text().strip());
        }
        return text.toString();
    }

    private static String marker(int index) {
        String[] circles = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨"};
        return index >= 1 && index <= circles.length ? circles[index - 1] : "(" + index + ")";
    }
}
