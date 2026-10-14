package io.github.keyboard01.assistant.observe;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.keyboard01.assistant.observe.ObservationModels.FeedbackSummary;
import io.github.keyboard01.assistant.security.AppUser;

/**
 * 用户反馈（P3）：赞 / 踩 + 差评原因。
 *
 * <p><b>为什么值得单独做一条路。</b>评测题集只有 60 道人工题，覆盖面靠人写；
 * 真实用户点一次"踩"并选出"答非所问"，就是一条带着标签的失败样本——这是最便宜的评测数据来源，
 * 而且它指向的正是线上真实分布的问题（人工题集永远猜不到用户会怎么问）。</p>
 *
 * <p><b>为什么原因要白名单。</b>自由文本没法聚合，聚不了类也就指导不了优化：
 * 100 条"不太行"和 100 条"答非所问"对排期的作用完全不同。所以原因只能是固定的几个词，
 * 想说的话放进 comment。</p>
 *
 * <p><b>权限口径。</b>只有提问人能给自己的问答评价（管理员可代评）。这不是"防谁"，
 * 而是让这条数据可信：任何人都能给任何一条账本打分的话，聚合出来的满意率就没意义了。</p>
 */
@Service
public class FeedbackService {

    public static final int LIKE = 1;
    public static final int DISLIKE = -1;

    /** 差评原因白名单。前端按同一份枚举渲染 chips，改这里要同步改前端。 */
    public static final List<String> REASONS = List.of("答非所问", "引用不对", "内容不全", "已过期", "其他");

    /** 补充说明的长度上限：够说清问题，又不至于把一行账本变成一个文本框。 */
    public static final int MAX_COMMENT = 500;

    private final QaTraceRepository traces;

    public FeedbackService(QaTraceRepository traces) {
        this.traces = traces;
    }

    /** 校验并归一化后的反馈。 */
    record Validated(int rating, String reason, String comment) {
    }

    /**
     * 提交反馈。同一条账本重复提交按最新覆盖（用户改主意是常事，留一堆历史评价只会让统计变成选择题）。
     *
     * <p>返回**归一化之后**的取值：调用方要按它回显，不能拿请求里的原值——给"赞"挂的原因会被丢掉，
     * 照着请求回显等于告诉用户"原因记下了"，而库里其实没有。</p>
     */
    public Validated submit(long traceId, Integer rating, String reason, String comment, AppUser user) {
        Validated valid = validate(rating, reason, comment);
        String asker = traces.findAsker(traceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这条问答记录：" + traceId));
        boolean admin = user.roleCode() != null && "admin".equals(user.roleCode().trim().toLowerCase(Locale.ROOT));
        if (!admin && !asker.equalsIgnoreCase(user.username())) {
            // 与"文档不存在"同一种处理：不告诉调用方这条记录属于谁
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能评价自己提出的问题");
        }
        traces.updateFeedback(traceId, valid.rating(), valid.reason(), valid.comment(), user.username());
        return valid;
    }

    public FeedbackSummary summary() {
        return traces.feedbackSummary();
    }

    /**
     * 纯函数校验（供单测）：rating 只认 ±1，原因只认白名单，且**只有差评才带原因**——
     * 给"赞"挂一个"内容不全"会让原因分布直接失去意义。
     */
    static Validated validate(Integer rating, String reason, String comment) {
        if (rating == null || (rating != LIKE && rating != DISLIKE)) {
            throw new IllegalArgumentException("评价只能是 1（有用）或 -1（没用）");
        }
        String cleanedReason = blankToNull(reason);
        if (rating == DISLIKE) {
            if (cleanedReason == null) {
                throw new IllegalArgumentException("点踩需要选择原因：" + String.join("、", REASONS));
            }
            if (!REASONS.contains(cleanedReason)) {
                throw new IllegalArgumentException("原因不在可选范围内：" + cleanedReason);
            }
        } else {
            cleanedReason = null;
        }
        String cleanedComment = blankToNull(comment);
        if (cleanedComment != null && cleanedComment.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("补充说明最多 " + MAX_COMMENT + " 字");
        }
        return new Validated(rating, cleanedReason, cleanedComment);
    }

    private static String blankToNull(String value) {
        return Optional.ofNullable(value).map(String::strip).filter(text -> !text.isEmpty()).orElse(null);
    }
}
