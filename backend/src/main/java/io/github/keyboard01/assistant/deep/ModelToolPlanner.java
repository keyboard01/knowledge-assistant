package io.github.keyboard01.assistant.deep;

import java.io.IOException;
import java.util.Optional;

import io.github.keyboard01.assistant.generation.LanguageModel;

/**
 * 用生成模型做 S6 的决策者（JSON 协议，不依赖各家 SDK 的 tool-calling 形状）。
 *
 * <p>为什么不用真正的 function calling：模型出口是 OpenAI 兼容的 HTTP 适配层，各服务商对
 * {@code tools} / {@code tool_calls} 的支持度参差不齐（推理型模型尤其），而这条链路上
 * "能不能决策"比"用哪种协议决策"重要得多。代价是多花一点提示词，以及要能容忍模型输出前后带废话
 * ——{@link ToolCall#parse} 已经在兜这个底。</p>
 */
public class ModelToolPlanner implements DeepToolPlanner {

    static final String SYSTEM = """
            你在为企业知识助手做检索策略。你会看到：原始问题、每个子问题目前的证据覆盖情况、
            已经试过的检索词、以及剩余预算。

            你只能选一个动作，并且只输出一个 JSON 对象（不要解释、不要 Markdown 代码块）：
            {"tool":"search","question":"..."}                                   // 换关键词或换角度再检索一次
            {"tool":"read_document","documentId":8,"fromChunkIndex":12,"limit":6} // 继续读某份文档的后续片段
            {"tool":"finish"}                                                     // 证据已经够回答，开始作答

            判断要点：
            1. 先看哪个子问题还没有对应证据；优先给它换一个更具体的检索词（型号、编号、专业术语原样保留）。
            2. 证据只覆盖到标题、表头、图注这类"壳"，而问题要的是表里的数据时，用 read_document
               从已读到的那段之后继续读同一份文档。
            3. 已经试过的检索词不要再用；同一份文档同一段不要重复读。
            4. 只要证据已经能支撑回答，就 finish。不要为了显得努力而多查一轮。
            """;

    private final LanguageModel languageModel;
    private final DeepLedger ledger;

    public ModelToolPlanner(LanguageModel languageModel, DeepLedger ledger) {
        this.languageModel = languageModel;
        this.ledger = ledger;
    }

    @Override
    public Optional<ToolCall> next(String digest) throws IOException, InterruptedException {
        LanguageModel.Completion completion = languageModel.complete(SYSTEM, digest);
        ledger.chargeTokens(completion.promptTokens(), completion.completionTokens());
        ledger.noteModel(completion.model());
        return ToolCall.parse(completion.content());
    }
}
