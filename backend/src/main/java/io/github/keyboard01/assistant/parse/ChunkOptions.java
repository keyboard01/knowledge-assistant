package io.github.keyboard01.assistant.parse;

/**
 * 切分预算（三段式），单位是**估算 token**而不是字符。中文近似 1 字 1 token、英文按词，
 * 所以必须先折算成 token 再比大小——按字符切会让中英混排的片段实际长度差出一截。
 *
 * <p>三段各有分工：</p>
 * <ul>
 *   <li>{@code maxTokens}：单片段的硬上限，超长单句按字符硬切；</li>
 *   <li>{@code targetTokens}：长段落按句打包的软目标，攒到这就出一片；</li>
 *   <li>{@code minTokens}：低于此视为“短块”，可并入同一章节路径、同一类型的相邻块；</li>
 *   <li>{@code overlapTokens}：相邻片段保留的尾部重叠，防止答案正好卡在片段边界被切两半。</li>
 * </ul>
 *
 * <p>用 {@link #of} 构造以做归一化；直接 new 不做校验（留给 {@link #DEFAULT} 这类已知合法的常量）。</p>
 */
public record ChunkOptions(int minTokens, int targetTokens, int maxTokens, int overlapTokens) {

    /** 贴近现状（精确优先）的默认档：等价于旧实现的 200 字，只是改用 token 口径。 */
    public static final ChunkOptions DEFAULT = new ChunkOptions(60, 150, 200, 30);

    /**
     * 归一化构造：保证 {@code 1 <= minTokens <= targetTokens <= maxTokens}，
     * 且 {@code 0 <= overlapTokens < targetTokens}。配置写反了也不至于让切分逻辑崩。
     */
    public static ChunkOptions of(int minTokens, int targetTokens, int maxTokens, int overlapTokens) {
        int max = Math.max(1, maxTokens);
        int target = clamp(targetTokens, 1, max);
        int min = clamp(minTokens, 1, target);
        int overlap = clamp(overlapTokens, 0, Math.max(0, target - 1));
        return new ChunkOptions(min, target, max, overlap);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
