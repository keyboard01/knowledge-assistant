package com.hykj.assistant.generation;

import java.util.List;

/**
 * 一次生成的账本：AC-P1-011 要的"命中片段、耗时、token 与费用"落在这里。
 *
 * <p>{@code refusalRule} 是机器可读的拒答原因码（谁都不信模型自觉，拒答也一样要能统计）。
 * 费用没有直接金额：单价随模型与账期变化，账本只记 token，换算留给 T10。</p>
 */
public record GenerationDiagnostics(String model, boolean modelCalled, int evidenceCount, int citedCount,
                                    List<Integer> outOfRangeCitations, int promptTokens, int completionTokens,
                                    String refusalRule, boolean truncated, long elapsedMs) {
}
