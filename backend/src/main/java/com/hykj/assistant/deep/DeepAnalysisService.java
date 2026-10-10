package com.hykj.assistant.deep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hykj.assistant.deep.DeepModels.DeepAnswer;
import com.hykj.assistant.deep.DeepModels.DeepDiagnostics;
import com.hykj.assistant.deep.DeepModels.Section;
import com.hykj.assistant.deep.DeepModels.Step;
import com.hykj.assistant.deep.DeepModels.SubQuestion;
import com.hykj.assistant.domain.DomainRepository;
import com.hykj.assistant.domain.KnowledgeDomain;
import com.hykj.assistant.generation.AnswerComposer;
import com.hykj.assistant.generation.LanguageModel;
import com.hykj.assistant.ingest.DocumentRepository;
import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalService;
import com.hykj.assistant.security.AccessSubject;
import com.hykj.assistant.security.DocumentAclRepository;

/**
 * P3a 的编排：S5 拆解 → S6 工具循环 → S7 退出判断 → S8 分步作答与引用校验。
 *
 * <p>它自己不做任何判定，判定全在 {@link DeepPlan} / {@link EvidenceExplorer} / {@link GapJudge} /
 * {@link StepwiseAnswerer} 这四个纯函数较多的地方——这里只负责"按什么顺序做、什么时候停、账怎么记"。
 * 这跟 {@code GenerationService} 的分工是同一条理由：编排里塞判定，最后没人说得清哪一条规则在生效。</p>
 *
 * <p>安全边界与快速模式**共用同一套**：身份取自会话（{@link AccessSubject}），可见范围与知识域
 * 由 {@link RetrievalTools} 在实现里补全，模型能填的参数里没有它们。深度通道多出来的能力是
 * "多查几轮、还能顺着片段往后读"，不是"能看见更多"。</p>
 */
@Service
public class DeepAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DeepAnalysisService.class);

    /** 深度通道的模式名，进响应与日志：同一个问题走了哪条路，事后必须能分清。 */
    public static final String MODE = "deep";

    static final String STOP_CLARIFY = "CLARIFY";

    /** 取消不是失败：已经查到的部分是有效的，只是用户不等了。 */
    static final String CANCEL_NOTICE = "本次深度分析已取消，下面是已经查到的部分";

    /** 流式回调。默认实现都是空动作，所以 JSON 入口传 {@link #NONE} 就行。 */
    public interface DeepListener {

        DeepListener NONE = new DeepListener() {
        };

        default void onPlan(String runId, List<SubQuestion> plan, DeepBudget budget) {
        }

        default void onStep(Step step) {
        }

        default void onEvidences(List<Evidence> evidences) {
        }

        default void onDelta(String text) {
        }

        default void onSection(Section section) {
        }

        /** 只有流式入口要把正文边生成边推出去；非流式走 complete，少一个并发面。 */
        default boolean streaming() {
            return false;
        }
    }

    private final RetrievalService retrievalService;
    private final DocumentRepository documents;
    private final DocumentAclRepository aclRepository;
    private final DeepChunkRepository chunks;
    private final DomainRepository domains;
    private final LanguageModel languageModel;
    private final QuestionDecomposer decomposer;
    private final StepwiseAnswerer stepwise;
    private final DeepRunRegistry registry;

    public DeepAnalysisService(RetrievalService retrievalService, DocumentRepository documents,
                               DocumentAclRepository aclRepository, DeepChunkRepository chunks,
                               DomainRepository domains, LanguageModel languageModel,
                               QuestionDecomposer decomposer, StepwiseAnswerer stepwise, DeepRunRegistry registry) {
        this.retrievalService = retrievalService;
        this.documents = documents;
        this.aclRepository = aclRepository;
        this.chunks = chunks;
        this.domains = domains;
        this.languageModel = languageModel;
        this.decomposer = decomposer;
        this.stepwise = stepwise;
        this.registry = registry;
    }

    public DeepAnswer answer(AccessSubject subject, String domainCode, String question, DeepBudget budget,
                             String requestedRunId, DeepListener listener)
            throws IOException, InterruptedException {
        String asked = require(question, "问题不能为空");
        String code = require(domainCode, "请选择知识域");
        // 知识域先校验：为一个不存在的域去花一次拆解 token 是纯浪费，而且错误信息会绕远。
        KnowledgeDomain domain = domains.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        if (!domain.enabled()) {
            throw new IllegalArgumentException("知识域已停用：" + domain.name());
        }
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedRunId.strip();
        DeepListener sink = listener == null ? DeepListener.NONE : listener;
        // 登记在跑之前：取消请求随时可能到，晚一步登记就晚一步可取消。
        DeepLedger ledger = registry.register(runId, subject.username(), budget);
        try {
            return run(subject, domain, asked, runId, ledger, sink);
        } finally {
            registry.finish(runId);
        }
    }

    private DeepAnswer run(AccessSubject subject, KnowledgeDomain domain, String question, String runId,
                           DeepLedger ledger, DeepListener sink) throws IOException, InterruptedException {
        DeepPlan.Plan plan = decomposer.decompose(question, ledger.budget().maxSubQuestions(), ledger);
        ledger.chargeSubQuestions(plan.subQuestions().size());
        sink.onPlan(runId, plan.subQuestions(), ledger.budget());

        // 澄清也要"先查后问"：模型判"缺关键限定"时，限定条件常常就写在知识库里（住宿标准按城市分档），
        // 一上来就反问等于把答得上的问题推回给用户。所以照常探索，查不到证据才轮到澄清。
        EvidenceExplorer.Exploration exploration = explore(subject, domain, question, plan, ledger, sink);
        DeepEvidenceMerger.Merged merged = exploration.merged();
        List<Evidence> evidences = merged.evidences();
        List<Step> steps = exploration.steps();
        int uncovered = exploration.uncoveredSubQuestions();
        int newFragments = exploration.newFragmentsLastRound();
        String hint = exploration.hint();
        String accessNotice = exploration.accessNotice();
        String stopReason = exploration.stopReason();
        String stopMessage = exploration.stopMessage();

        GapJudge.Decision decision = GapJudge.decide(evidences.size(), uncovered, plan.clarifyQuestion(),
                budgetStop(ledger), newFragments);

        if (decision.action() == GapJudge.Action.CLARIFY) {
            // 停止原因跟着出口走：这里的 stopReason 来自探索循环（可能是 DONE），而用户看到的这件事
            // 是"反问你一句"，两个口径混在一起，前端就没法解释这次为什么没有答案。
            return clarified(subject, domain, question, runId, plan, steps, ledger, decision.text(), STOP_CLARIFY,
                    "问题缺少关键限定，先向用户澄清");
        }
        if (decision.action() == GapJudge.Action.REFUSE) {
            return refused(subject, domain, question, runId, plan, steps, ledger, hint, accessNotice,
                    decision.text(), stopReason, stopMessage);
        }
        StepwiseAnswerer.Result result = stepwise.answer(plan, merged, ledger,
                sink.streaming() ? sink::onDelta : null, sink::onSection);
        return answered(subject, domain, question, runId, plan, steps, ledger, evidences, result, decision,
                accessNotice, stopReason, stopMessage);
    }

    private EvidenceExplorer.Exploration explore(AccessSubject subject, KnowledgeDomain domain, String question,
                                                 DeepPlan.Plan plan, DeepLedger ledger, DeepListener sink)
            throws IOException, InterruptedException {
        RetrievalTools tools = new RetrievalTools(subject, domain.code(), retrievalService, documents,
                aclRepository, chunks);
        // 预算里没给模型留轮数时，干脆不给它决策者：省一次模型调用，也少一个变量。
        DeepToolPlanner planner = ledger.roundsLeft() ? new ModelToolPlanner(languageModel, ledger) : null;
        EvidenceExplorer.Exploration exploration = new EvidenceExplorer(tools, planner, ledger, sink::onStep)
                .explore(question, plan);
        sink.onEvidences(exploration.merged().evidences());
        return exploration;
    }

    private DeepAnswer clarified(AccessSubject subject, KnowledgeDomain domain, String question, String runId,
                                 DeepPlan.Plan plan, List<Step> steps, DeepLedger ledger, String clarify,
                                 String stopReason, String stopMessage) {
        String answer = clarify == null ? DeepPlan.DEFAULT_CLARIFY : clarify;
        DeepAnswer result = new DeepAnswer(runId, domain.code(), question, MODE, answer, List.of(),
                plan.subQuestions(), steps, List.of(), false, null, null, answer, code(ledger, stopReason),
                stopMessage, List.of(), diagnostics(ledger, plan, null, List.of(), code(ledger, stopReason)));
        logSummary(subject, domain, plan, ledger, result);
        return result;
    }

    private DeepAnswer refused(AccessSubject subject, KnowledgeDomain domain, String question, String runId,
                               DeepPlan.Plan plan, List<Step> steps, DeepLedger ledger, String hint,
                               String accessNotice, String decisionText, String stopReason, String stopMessage) {
        String answer = unableText(hint, accessNotice, decisionText);
        String code = code(ledger, stopReason);
        DeepAnswer result = new DeepAnswer(runId, domain.code(), question, MODE, answer, List.of(),
                plan.subQuestions(), steps, List.of(), true, AnswerComposer.RULE_INSUFFICIENT, null, null, code,
                stopMessage, List.of(), diagnostics(ledger, plan, null, List.of(), code));
        logSummary(subject, domain, plan, ledger, result);
        return result;
    }

    private DeepAnswer answered(AccessSubject subject, KnowledgeDomain domain, String question, String runId,
                                DeepPlan.Plan plan, List<Step> steps, DeepLedger ledger, List<Evidence> evidences,
                                StepwiseAnswerer.Result result, GapJudge.Decision decision, String accessNotice,
                                String stopReason, String stopMessage) {
        List<Section> sections = result.sections();
        boolean allRefused = sections.stream().noneMatch(Section::answered);
        String refusalRule = null;
        String answer = result.joined();
        String notice;
        if (allRefused) {
            // 一段都没答上来：按"无法回答"处理，并把每一段自己的原因保留在正文里——
            // 那比一句统一的"答不了"更能定位问题，也让用户看得见到底缺哪一部分。
            answer = unableText(null, accessNotice, decision.text());
            String detail = result.joined().strip();
            if (!detail.isEmpty()) {
                answer = answer + "\n" + detail;
            }
            refusalRule = sections.stream().map(Section::refusalRule).filter(Objects::nonNull).findFirst()
                    .orElse(AnswerComposer.RULE_INSUFFICIENT);
            notice = null;
        } else {
            notice = joinNotice(accessNotice, decision.text(), ledger.cancelled() ? CANCEL_NOTICE : null);
        }
        String code = code(ledger, stopReason);
        DeepAnswer deepAnswer = new DeepAnswer(runId, domain.code(), question, MODE, answer, sections,
                plan.subQuestions(), steps, result.citations(), allRefused, refusalRule, notice, null, code,
                stopMessage, evidences, diagnostics(ledger, plan, result, evidences, code));
        logAnswer(subject, domain, plan, ledger, deepAnswer, result);
        return deepAnswer;
    }

    private static DeepDiagnostics diagnostics(DeepLedger ledger, DeepPlan.Plan plan, StepwiseAnswerer.Result result,
                                               List<Evidence> evidences, String stopReason) {
        int sections = result == null ? 0 : result.sections().size();
        int answered = result == null ? 0 : result.answeredSections();
        int cited = result == null ? 0 : result.citations().size();
        return new DeepDiagnostics(ledger.model(), ledger.promptTokens() + ledger.completionTokens() > 0,
                plan.subQuestions().size(), sections, answered, evidences.size(), cited, ledger.toolCalls(),
                ledger.rounds(), ledger.promptTokens(), ledger.completionTokens(), ledger.elapsedMs(),
                budgetStop(ledger).map(DeepLedger.Stop::message).orElse(null), stopReason);
    }

    /** 取消与预算共用一条停止出口，但**顺序不能反**：取消是用户的决定，预算才是系统拦下的。 */
    private static String code(DeepLedger ledger, String stopReason) {
        return ledger.cancelled() ? DeepLedger.CODE_CANCELLED : stopReason;
    }

    /**
     * 预算触顶的出口。
     *
     * <p>被取消时返回空：取消不是"预算用完了"，混在同一句提示里，用户会以为是我们小气。</p>
     */
    private static Optional<DeepLedger.Stop> budgetStop(DeepLedger ledger) {
        return ledger.cancelled() ? Optional.empty() : ledger.stop();
    }

    private static String unableText(String hint, String accessNotice, String decisionText) {
        StringBuilder text = new StringBuilder("根据知识库中现有的内容无法可靠回答这个问题。");
        if (accessNotice != null && !accessNotice.isBlank()) {
            text.append(' ').append(accessNotice.strip()).append('。');
        }
        if (hint != null && !hint.isBlank()) {
            text.append(' ').append(hint.strip());
        }
        if (decisionText != null && !decisionText.isBlank()) {
            text.append(' ').append(decisionText.strip()).append('。');
        }
        return text.toString();
    }

    private static String joinNotice(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                kept.add(part.strip());
            }
        }
        return kept.isEmpty() ? null : String.join("；", kept);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static void logSummary(AccessSubject subject, KnowledgeDomain domain, DeepPlan.Plan plan,
                                   DeepLedger ledger, DeepAnswer answer) {
        log.info("深度分析 user={} domain={} 子问题={} 工具调用={} 轮={} 结论={} 停止={} token={}/{} 耗时={}ms question=\"{}\"",
                subject.username(), domain.code(), plan.subQuestions().size(), ledger.toolCalls(), ledger.rounds(),
                answer.refused() ? "拒答" : answer.clarifyQuestion() != null ? "澄清" : "作答", answer.stopReason(),
                ledger.promptTokens(), ledger.completionTokens(), ledger.elapsedMs(), answer.question());
    }

    private static void logAnswer(AccessSubject subject, KnowledgeDomain domain, DeepPlan.Plan plan,
                                  DeepLedger ledger, DeepAnswer answer, StepwiseAnswerer.Result result) {
        log.info("深度分析 user={} domain={} 子问题={} 工具调用={} 轮={} 证据={} 引用={} 段={}/{} 生成调用={} "
                        + "拒答={} 停止={} token={}/{} 耗时={}ms question=\"{}\"",
                subject.username(), domain.code(), plan.subQuestions().size(), ledger.toolCalls(), ledger.rounds(),
                answer.evidences().size(), answer.citations().size(), result.answeredSections(),
                result.sections().size(), result.modelCalls(), answer.refused(), answer.stopReason(),
                ledger.promptTokens(), ledger.completionTokens(), ledger.elapsedMs(), answer.question());
    }
}
