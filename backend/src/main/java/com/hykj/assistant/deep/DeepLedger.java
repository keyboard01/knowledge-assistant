package com.hykj.assistant.deep;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 一次深度问答的流水账：预算 + 已用 + 取消位。
 *
 * <p>**每个请求一个实例**，不是单例——它是请求内部的状态，共享出去等于把两次问答的预算搅在一起。</p>
 *
 * <p>"能不能再走一步"只在这里判断，循环里只问 {@link #stop()}：判断散落在编排代码里，迟早会出现
 * 某一处忘了查预算。反过来，"这一轮该不该开始"由调用方先问 {@link #roundsLeft()}，因为轮数是
 * 轮的开销，不是步的开销。</p>
 *
 * <p>耗时检查发生在**步骤边界**上：一次模型调用本身可能超出剩余额度，服务端能保证的是
 * "不再开始新的一步"，而不是掐断已经发出的请求——后者只能靠上游超时。</p>
 */
public final class DeepLedger {

    /** 停止原因：{@code code} 机器可读（进响应与日志），{@code message} 给人看。 */
    public record Stop(String code, String message) {
    }

    public static final String CODE_CANCELLED = "CANCELLED";
    public static final String CODE_TOOL_BUDGET = "TOOL_CALL_BUDGET";
    public static final String CODE_ROUND_BUDGET = "ROUND_BUDGET";
    public static final String CODE_ELAPSED_BUDGET = "ELAPSED_BUDGET";
    public static final String CODE_TOKEN_BUDGET = "TOKEN_BUDGET";

    private final DeepBudget budget;
    private final LongSupplier clock;
    private final long startedAtNanos;
    private int subQuestions;
    private int rounds;
    private int toolCalls;
    private int promptTokens;
    private int completionTokens;
    private String model;
    private volatile boolean cancelled;

    public DeepLedger(DeepBudget budget) {
        this(budget, System::nanoTime);
    }

    /** 包内可见是为了单测：耗时触顶这条判据，用真时钟测不出来（要么慢，要么不稳）。 */
    DeepLedger(DeepBudget budget, LongSupplier clock) {
        this.budget = budget;
        this.clock = clock;
        this.startedAtNanos = clock.getAsLong();
    }

    public DeepBudget budget() {
        return budget;
    }

    /** 取消是外部线程调用的（cancel 接口），所以是 volatile；计数只在自己的线程里改。 */
    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void chargeSubQuestions(int count) {
        subQuestions = Math.max(0, count);
    }

    public int subQuestions() {
        return subQuestions;
    }

    public boolean roundsLeft() {
        return rounds < budget.maxRounds();
    }

    /** 开始新的一轮（调用方先确认 {@link #roundsLeft()}）。 */
    public void startRound() {
        rounds++;
    }

    public int rounds() {
        return rounds;
    }

    /**
     * 记一次工具调用。
     *
     * <p>先记后查还是先查后记：这里是**先查后记**（调用方问完 {@link #stop()} 才执行、执行完才记账），
     * 所以 {@code toolCalls} 永远 ≤ 上限，不会出现"最后一次把预算撑爆"。</p>
     */
    public void chargeTool() {
        toolCalls++;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public void chargeTokens(int prompt, int completion) {
        promptTokens += Math.max(0, prompt);
        completionTokens += Math.max(0, completion);
    }

    /** 记下模型名（第一次见到的为准）：账本要能回答"这次是哪家模型答的"，而不是靠翻日志。 */
    public void noteModel(String name) {
        if (model == null && name != null && !name.isBlank()) {
            model = name.trim();
        }
    }

    public String model() {
        return model == null ? "" : model;
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public long elapsedMs() {
        return Math.max(0, (clock.getAsLong() - startedAtNanos) / 1_000_000L);
    }

    /** 还能不能再走一步？{@code empty} = 可以。轮数不在这里判，见 {@link #roundsLeft()}。 */
    public Optional<Stop> stop() {
        if (cancelled) {
            return Optional.of(new Stop(CODE_CANCELLED, "收到取消请求，已停止后续检索"));
        }
        if (toolCalls >= budget.maxToolCalls()) {
            return Optional.of(new Stop(CODE_TOOL_BUDGET, "已达最大工具调用次数 " + budget.maxToolCalls()));
        }
        if (elapsedMs() >= budget.maxElapsedMs()) {
            return Optional.of(new Stop(CODE_ELAPSED_BUDGET, "已达最大耗时 " + budget.maxElapsedMs() + "ms"));
        }
        if (completionTokens >= budget.maxCompletionTokens()) {
            return Optional.of(new Stop(CODE_TOKEN_BUDGET, "已达输出 token 预算 " + budget.maxCompletionTokens()));
        }
        return Optional.empty();
    }

    /**
     * 还能不能为"生成答案"再花一次 token。
     *
     * <p>与 {@link #stop()} 分开是因为两者的取舍不同：检索还可以少查一轮，而**回答是必须发生的一步**
     * ——为了省 token 连已经找到的证据都不答，等于前面全白花。所以这里只拦取消与 token 预算。</p>
     */
    public Optional<Stop> stopForGeneration() {
        if (cancelled) {
            return Optional.of(new Stop(CODE_CANCELLED, "收到取消请求，已停止生成"));
        }
        if (completionTokens >= budget.maxCompletionTokens()) {
            return Optional.of(new Stop(CODE_TOKEN_BUDGET, "已达输出 token 预算 " + budget.maxCompletionTokens()));
        }
        return Optional.empty();
    }
}
