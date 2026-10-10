package io.github.keyboard01.assistant.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 RRF 融合结果按 cross-encoder 的相关性分重新排序。纯函数，有单测。
 *
 * <p>排序键：{@code rerankScore desc → fusedScore desc → vectorScore desc → chunkId asc}。
 * 前一位定不了名次时逐位退化为引入重排前的顺序，所以"重排分全部相同"等价于不重排——
 * 这让 {@link io.github.keyboard01.assistant.adapters.rerank.HttpReranker} 的熔断/fail-open 有一个干净的落点。</p>
 *
 * <p>校验放在这里而不是调用方：分数条数与候选不一致、或某个分数是 null，都说明这次重排不可信，
 * 抛 {@link IllegalArgumentException} 让调用方 fail-open 退回原序，绝不半信半疑地重排一半。</p>
 */
public final class RerankStage {

    /** 一条候选及其重排分。 */
    public record Scored(FusedChunk chunk, double rerankScore) {
    }

    private RerankStage() {
    }

    public static List<Scored> apply(List<FusedChunk> candidates, List<Double> scores) {
        if (candidates.size() != scores.size()) {
            throw new IllegalArgumentException(
                    "重排分数条数与候选不一致：" + scores.size() + "/" + candidates.size());
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Double score = scores.get(i);
            if (score == null) {
                throw new IllegalArgumentException("第 " + i + " 条候选缺少重排分");
            }
            scored.add(new Scored(candidates.get(i), score));
        }
        Comparator<Scored> order = Comparator
                .comparingDouble(Scored::rerankScore).reversed()
                .thenComparing(Comparator.comparingDouble((Scored item) -> item.chunk().fusedScore()).reversed())
                .thenComparing(Comparator.comparingDouble((Scored item) -> item.chunk().vectorScore()).reversed())
                .thenComparingLong(item -> item.chunk().chunk().chunkId());
        scored.sort(order);
        return List.copyOf(scored);
    }
}
