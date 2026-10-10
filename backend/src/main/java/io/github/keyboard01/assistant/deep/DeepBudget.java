package io.github.keyboard01.assistant.deep;

/**
 * 深度通道的预算（S6 / 设计文档 §4.6）。
 *
 * <p>预算必须由服务端定死：模型没有"花多少钱"的概念，它只会"再想一次"。这里的每个数都是**上限**，
 * 请求方只能往下调（{@link #of}）——否则"任何情况下不超预算"这条验收就变成客户端说了算。</p>
 *
 * <p>为什么这些数字是分开的而不是一个"最大步数"：模型的三种浪费方式不一样——多轮检索烧的是时间与
 * 检索成本，啰嗦的长答案烧的是输出 token，死循环烧的是轮数。合成一个数就说不清是哪一种先把预算吃掉了。</p>
 */
public record DeepBudget(int maxSubQuestions, int maxToolCalls, int maxRounds, long maxElapsedMs,
                         int maxCompletionTokens) {

    /** 服务端上限：任何请求都越不过这几个数。 */
    public static final DeepBudget CEILING = new DeepBudget(4, 10, 3, 120_000L, 12_000);

    /** 缺省预算。先用满上限，触到瓶颈再往下调——调低是运维动作，不该需要发版。 */
    public static final DeepBudget DEFAULT = CEILING;

    public DeepBudget {
        if (maxSubQuestions < 1 || maxToolCalls < 1 || maxRounds < 1 || maxElapsedMs < 1
                || maxCompletionTokens < 1) {
            throw new IllegalArgumentException("深度预算的每一项都必须大于 0");
        }
    }

    /**
     * 请求里带的预算：逐项按上限收紧，未给的项用服务端缺省值。
     *
     * <p>给得比上限还大**不报错**，按上限算：报错会让"调低预算"和"越权调高"两种行为长得一样，
     * 而它们该得到完全不同的处置。</p>
     */
    public static DeepBudget of(Integer maxSubQuestions, Integer maxToolCalls, Integer maxRounds,
                                Long maxElapsedMs, Integer maxCompletionTokens) {
        return new DeepBudget(
                narrow(maxSubQuestions, DEFAULT.maxSubQuestions(), CEILING.maxSubQuestions()),
                narrow(maxToolCalls, DEFAULT.maxToolCalls(), CEILING.maxToolCalls()),
                narrow(maxRounds, DEFAULT.maxRounds(), CEILING.maxRounds()),
                narrow(maxElapsedMs, DEFAULT.maxElapsedMs(), CEILING.maxElapsedMs()),
                narrow(maxCompletionTokens, DEFAULT.maxCompletionTokens(), CEILING.maxCompletionTokens()));
    }

    /** 进日志与响应的一句话，省得排障时还要把几个数拼回去。 */
    public String describe() {
        return "子问题≤" + maxSubQuestions + " 工具调用≤" + maxToolCalls + " 轮数≤" + maxRounds
                + " 耗时≤" + (maxElapsedMs / 1000) + "s 输出token≤" + maxCompletionTokens;
    }

    private static int narrow(Integer value, int fallback, int ceiling) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, 1), ceiling);
    }

    private static long narrow(Long value, long fallback, long ceiling) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, 1L), ceiling);
    }
}
