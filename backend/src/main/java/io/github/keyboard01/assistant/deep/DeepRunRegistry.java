package io.github.keyboard01.assistant.deep;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 正在运行的深度分析登记表。存在的唯一理由是"可取消"：取消请求是另一个请求打进来的，
 * 它必须能找到那次运行，而 HTTP 连接本身给不了这个把手。
 *
 * <p>几个刻意的取舍：</p>
 * <ul>
 *   <li><b>取消只是置位，不强杀线程</b>：循环在步骤边界上检查。强杀会留下"证据查一半、账算一半"的状态，
 *       而深度分析的每一步都是有意义的半成品——用户可以要它，也可以不要。</li>
 *   <li><b>别人的 runId 取消不了，而且不告诉他为什么</b>：返回的文案与"运行不存在"完全一样。
 *       "这个 id 存在但不属于你"本身就是一条信息。</li>
 *   <li><b>运行结束即摘除</b>：这是一张短命的表，不是审计日志——审计由应用日志承担。</li>
 * </ul>
 */
@Component
public class DeepRunRegistry {

    private static final String NOT_FOUND = "该运行已结束或不存在";

    private record Run(String username, DeepLedger ledger) {
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public DeepLedger register(String runId, String username, DeepBudget budget) {
        DeepLedger ledger = new DeepLedger(budget);
        runs.put(runId, new Run(username, ledger));
        return ledger;
    }

    public void finish(String runId) {
        runs.remove(runId);
    }

    public Cancel cancel(String runId, String username) {
        if (runId == null || runId.isBlank()) {
            return new Cancel(false, "缺少 runId");
        }
        Run run = runs.get(runId.strip());
        if (run == null || !run.username().equals(username)) {
            return new Cancel(false, NOT_FOUND);
        }
        run.ledger().cancel();
        return new Cancel(true, "已请求取消，将在当前步骤结束时停止");
    }

    public record Cancel(boolean cancelled, String note) {
    }

    /** 当前在跑的次数，自检与运维用。 */
    public int running() {
        return runs.size();
    }
}
