package com.hykj.assistant.deep;

import java.util.Optional;

/**
 * S7：现在够不够回答了？
 *
 * <p>规则能判的（有没有证据、预算到没到、本轮有没有新东西、哪些子问题还空着）一律不花 token 去问模型。
 * 理由很实际：退出判断是**每次**都要做的一次判断，把它交给模型，等于每次问答都多一次可能出错的调用；
 * 而这几条判据一个都不含糊。</p>
 *
 * <p>四个出口对应四种完全不同的处置：</p>
 * <ul>
 *   <li>{@code CLARIFY}：一条证据都没有，而拆解阶段判定"问题缺关键限定"——先反问再查。
 *       <b>它排在查证据之后</b>：能查到就不该反问用户。模型判"缺限定"依据的只是问法本身，
 *       而限定条件往往就写在知识库里（住宿标准按城市分档），先查一轮才分得清"你没说清"和"库里没有"。</li>
 *   <li>{@code REFUSE}：一条证据都没有——这是"知识库里没有"，不是"我们没查够"。</li>
 *   <li>{@code ANSWER_PARTIAL}：有证据，但有子问题没覆盖——**先答能答的**，并说明缺哪部分。
 *       这正是 §14 根因 2 的修法：过去的做法是整段拒答，用户拿到的是"答不了"，而不是"先给一半"。</li>
 *   <li>{@code ANSWER}：证据齐了，或者已经查不动了（预算/无新增）——后者是**正常结束**，不是异常。</li>
 * </ul>
 */
public final class GapJudge {

    public enum Action {
        CLARIFY, REFUSE, ANSWER_PARTIAL, ANSWER
    }

    /** {@code text} 是要说给用户的一句说明；为 null 表示无需额外提示。 */
    public record Decision(Action action, String text) {
    }

    private GapJudge() {
    }

    public static Decision decide(int evidenceCount, int uncoveredSubQuestions, String clarifyQuestion,
                                  Optional<DeepLedger.Stop> budgetStop, int newFragmentsLastRound) {
        if (evidenceCount == 0) {
            if (clarifyQuestion != null && !clarifyQuestion.isBlank()) {
                return new Decision(Action.CLARIFY, clarifyQuestion.strip());
            }
            return new Decision(Action.REFUSE, "检索没有带回任何可用证据，无法作答");
        }
        if (budgetStop.isPresent()) {
            return new Decision(Action.ANSWER,
                    "已达本次深度分析的预算上限（" + budgetStop.get().message() + "），答案可能不完整");
        }
        if (uncoveredSubQuestions > 0) {
            return new Decision(Action.ANSWER_PARTIAL,
                    "有 " + uncoveredSubQuestions + " 个子问题没有检索到证据，下面只回答有证据的部分");
        }
        if (newFragmentsLastRound == 0) {
            return new Decision(Action.ANSWER, "多轮检索已无新增证据，按现有证据作答");
        }
        return new Decision(Action.ANSWER, null);
    }
}
