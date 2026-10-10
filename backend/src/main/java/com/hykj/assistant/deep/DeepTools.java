package com.hykj.assistant.deep;

import java.io.IOException;
import java.util.List;

import com.hykj.assistant.retrieval.Evidence;

/**
 * Agent 能用的工具（设计文档 §4.5 的第一批五个里，本切片实现前两个）。
 *
 * <p>两个设计要点在这里是硬约束，不是注释：</p>
 * <ul>
 *   <li><b>参数由服务端补全</b>：接口上**没有身份、没有知识域**。模型只能给"问题"或"文档 ID + 片段序号"，
 *       它填不了自己看不见的东西——越权从"靠提示词约束"变成"类型上就传不进来"。</li>
 *   <li><b>返回值带定位信息</b>：每条证据都带文档 ID、版本、片段序号与页码，引用与后续校验才有落点。</li>
 * </ul>
 *
 * <p>{@code read_document} 是设计 §3.2"阅读整章或全文"的落点，也是 §14 根因 1 的修法：
 * 承载答案的表行排在第 7 名之后，靠一次检索拿不回来，只能沿着已命中的片段继续往后读。</p>
 */
public interface DeepTools {

    /** 检索一次。身份、可见范围、知识域由实现注入。 */
    Outcome search(String question) throws IOException, InterruptedException;

    /**
     * 读一份文档里从 {@code fromChunkIndex}（含）起的片段。
     *
     * <p>文档不存在与"没权限"返回**同一个空结果**：回答得像"这份文档没有内容"，才不会被拿来
     * 当探测工具（否则"读得到空"与"读不到"的区别本身就是一条信息）。</p>
     */
    Outcome readDocument(long documentId, Integer fromChunkIndex, int limit);

    /**
     * 这份文档在 {@code afterChunkIndex} 之后还有没有非空片段。
     *
     * <p>与"锚点之前那几段还没读过"合起来，就是补齐动作的开关：两边都没得补才整步跳过，
     * 别把预算花在"读到空"上。</p>
     */
    boolean hasMore(long documentId, int afterChunkIndex);

    /** 一次工具调用的产出。{@code blockedHitCount} 只对检索有意义，读取是逐条判权限，不产生这个数。 */
    record Outcome(List<Evidence> evidences, int blockedHitCount, String hint, String accessNotice) {

        public Outcome {
            evidences = List.copyOf(evidences);
        }

        public static Outcome empty() {
            return new Outcome(List.of(), 0, null, null);
        }
    }
}
