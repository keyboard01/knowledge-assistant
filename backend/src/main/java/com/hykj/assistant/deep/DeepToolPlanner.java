package com.hykj.assistant.deep;

import java.io.IOException;
import java.util.Optional;

/**
 * S6 里"下一步查什么"的决策者。
 *
 * <p>抽出接口是为了让探索循环能被单测：循环里真正容易错的是去重、预算与退出判断，那些跟
 * "决策是谁做的"无关——用假决策者就能把循环钉死，不必真调模型。</p>
 */
public interface DeepToolPlanner {

    /**
     * 给出下一步动作。
     *
     * @param digest 当前掌握的证据摘要（按人话整理好，含每个子问题的覆盖情况与已试检索词）
     * @return 空 = 没有下一步（模型说结束，或输出不可解析）。两种情况对循环来说都是"停"，
     *         区别只在日志里——所以不为了区分它们多设计一个返回值。
     */
    Optional<ToolCall> next(String digest) throws IOException, InterruptedException;
}
