package com.hykj.assistant.generation;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalResult;
import com.hykj.assistant.retrieval.RetrievalService;
import com.hykj.assistant.observe.ObserveService;
import com.hykj.assistant.security.AccessSubject;

/**
 * 问答编排：检索 → 组装提示词 → 调模型 → 组装并校验答案。
 *
 * <p>它自己不判断对错，判定全在 {@link AnswerComposer} 里（纯函数、可单测）。这里只负责
 * 决定"要不要花这次 token"，以及把账本写进日志。</p>
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    /** 拒答时留痕的模型原文长度：够看清它编了什么，又不至于把日志撑爆。 */
    private static final int RAW_TRACE_LIMIT = 300;

    /**
     * 流式回调。
     *
     * <p>证据先推、答案后推，是为了让前端在模型还没吐第一个字的时候就能画出"检索到了哪几份文档"
     * 与引用卡片——等待感大半来自这段空白。</p>
     */
    public interface StreamListener {

        void onEvidences(List<Evidence> evidences, boolean insufficient);

        void onDelta(String text);
    }

    private final RetrievalService retrievalService;
    private final LanguageModel languageModel;
    private final ObserveService observeService;

    public GenerationService(RetrievalService retrievalService, LanguageModel languageModel,
                             ObserveService observeService) {
        this.retrievalService = retrievalService;
        this.languageModel = languageModel;
        this.observeService = observeService;
    }

    public GeneratedAnswer answer(AccessSubject subject, String domainCode, String question, Integer topN)
            throws IOException, InterruptedException {
        return generate(subject, domainCode, question, topN, null);
    }

    public GeneratedAnswer answerStreaming(AccessSubject subject, String domainCode, String question, Integer topN,
                                           StreamListener listener) throws IOException, InterruptedException {
        return generate(subject, domainCode, question, topN, listener);
    }

    private GeneratedAnswer generate(AccessSubject subject, String domainCode, String question, Integer topN,
                                     StreamListener listener) throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        RetrievalResult retrieval = retrievalService.search(subject, domainCode, question, topN, null);
        if (listener != null) {
            listener.onEvidences(retrieval.evidences(), retrieval.insufficient());
        }

        // 证据不足就不花这次 token：这一条是"省成本"，更是"少一个变量"——
        // 答不上来时，我们不需要知道模型会编什么。
        LanguageModel.Completion completion = null;
        if (!retrieval.insufficient()) {
            String system = EvidencePrompt.SYSTEM;
            String user = EvidencePrompt.user(question, retrieval.evidences());
            // 流式与非流式共用同一段提示词、同一份判定，区别只在"要不要边生成边推"
            completion = listener == null
                    ? languageModel.complete(system, user)
                    : languageModel.streamComplete(system, user, listener::onDelta);
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        boolean called = completion != null;
        String modelText = called ? completion.content() : null;

        GeneratedAnswer answer = AnswerComposer.compose(retrieval, modelText,
                new AnswerComposer.Meta(called ? completion.model() : "", called,
                        called && completion.truncated(),
                        called ? completion.promptTokens() : 0,
                        called ? completion.completionTokens() : 0,
                        elapsedMs));
        logAnswer(subject, domainCode, question, answer, modelText);
        recordTrace(subject, domainCode, question, retrieval, answer);
        return answer;
    }

    /**
     * 落运行记录（T10）。**账本写失败不能让问答失败**：答案已经算出来了，
     * 因为记账出问题就把它丢掉，是本末倒置。失败了留一条 error 日志，账缺一条能查出来。
     */
    private void recordTrace(AccessSubject subject, String domainCode, String question,
                             RetrievalResult retrieval, GeneratedAnswer answer) {
        try {
            observeService.record(subject, domainCode, question, retrieval, answer);
        } catch (RuntimeException ex) {
            log.error("运行记录写入失败（本次问答不受影响）：domain={} question=\"{}\"", domainCode, question, ex);
        }
    }

    private static void logAnswer(AccessSubject subject, String domainCode, String question,
                                  GeneratedAnswer answer, String modelText) {
        GenerationDiagnostics d = answer.diagnostics();
        log.info("生成 user={} domain={} 模型={} 调用模型={} 证据={} 引用={} 越界={} 拒答={} 规则={} token={}/{} 耗时={}ms question=\"{}\"",
                subject.username(), domainCode, d.model(), d.modelCalled(), d.evidenceCount(), d.citedCount(),
                d.outOfRangeCitations(), answer.refused(), d.refusalRule(), d.promptTokens(),
                d.completionTokens(), d.elapsedMs(), question);
        // AC-P1-002 要的证据是"确实没编造"，不是"代码里写了不编造"：拒答时把模型原文留痕，
        // 才能事后核对它到底是没答上来，还是答了但被我们拦下了。
        if (answer.refused() && d.modelCalled() && modelText != null && !modelText.isBlank()) {
            log.info("生成被拒（{}），模型原文留痕：{}", d.refusalRule(), trace(modelText));
        }
    }

    private static String trace(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > RAW_TRACE_LIMIT ? flat.substring(0, RAW_TRACE_LIMIT) + "..." : flat;
    }
}
