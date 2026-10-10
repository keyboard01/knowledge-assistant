package io.github.keyboard01.assistant.generation;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 生成模型的内部端口：业务侧只说"给我一段回答"，不关心是哪家模型、走什么协议。
 *
 * <p>和 {@link io.github.keyboard01.assistant.retrieval.KnowledgeRetriever} 一样，这是防腐层的接口一侧——
 * 换模型（推理服务 / 该服务 / 本地推理）只换适配层的实现类，生成流程一行不动。</p>
 */
public interface LanguageModel {

    Completion complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException;

    /**
     * 流式生成：每收到一段增量就回调一次，返回值与 {@link #complete} 同型。
     *
     * <p>流式只解决"等得久"的观感，不降低判定标准——引用校验必须看到完整答案才能做，
     * 所以调用方在收到完整结果之前，只能把流出去的内容当作"未校验"。</p>
     */
    Completion streamComplete(String systemPrompt, String userPrompt, Consumer<String> onDelta)
            throws IOException, InterruptedException;

    /**
     * 一次调用的结果。token 用量要进账本（AC-P1-011），{@code truncated} 用来识别"被 max_tokens 截断"
     * ——截断的回答可能丢掉"版本冲突"这类关键限定语，宁可拒答也不要给半句。
     */
    record Completion(String content, String model, int promptTokens, int completionTokens, boolean truncated) {
    }
}
