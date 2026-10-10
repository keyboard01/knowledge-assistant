package com.hykj.assistant.retrieval;

import java.io.IOException;
import java.util.List;

/**
 * 重排能力的内部契约（P0 rerank）。
 *
 * <p>RRF 只看"两路各排第几"，不看"问题与片段到底像不像"。当 top-N 里混进语义相近但答非所问的片段时，
 * 没有任何一层能把它压下去——这个接口就是那一层：在 RRF 之后、双阈值判定之前，用 cross-encoder
 * 给候选打一个真正的语义相关性分，按它重排名次。</p>
 *
 * <p>它只**改排序**，不改作答阈值口径：{@link RetrievedPassage#score()} 仍是向量余弦，
 * 重排分单独承载。这样现有按余弦标定的双阈值不失效；把阈值也换成重排口径需要先重跑标定，
 * 属于独立动作（见 {@code docs/差距清单与优先级.md} §7.1）。</p>
 *
 * <p>和 {@link com.hykj.assistant.generation.LanguageModel} 一样，这是防腐层的接口一侧：
 * 换 cross-encoder（本地 重排模型 / 云 rerank）只换适配层实现，检索编排一行不动。</p>
 */
public interface Reranker {

    /** 当前是否真的会重排：未配置、开关关闭、或刚失败处于熔断期时返回 false，调用方据此直接跳过。 */
    boolean enabled();

    /**
     * 给 query 与若干候选文档打语义相关性分，返回顺序与 {@code documents} 一一对应（越大越相关）。
     *
     * <p>条数必须与 {@code documents} 一致。条数不符、响应无法解析、或网络失败时抛异常——
     * <b>调用方负责 fail-open</b>（退回 RRF 原序，绝不让一次重排失败影响出不出结果）。</p>
     */
    List<Double> score(String query, List<String> documents) throws IOException, InterruptedException;

    /** 这条通道的人话名字，用来在自检/日志里说清"现在走的是哪条链路"。 */
    default String displayName() {
        return getClass().getSimpleName();
    }
}
