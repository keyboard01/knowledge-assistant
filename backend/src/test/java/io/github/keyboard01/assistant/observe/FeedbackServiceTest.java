package io.github.keyboard01.assistant.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 反馈校验（纯函数）。坏起来不报错、只脏数据的地方：
 * 给"赞"挂上差评原因、原因写成自由文本、评论超长——库能写进去，聚合出来的分布却是错的。
 */
class FeedbackServiceTest {

    @Test
    @DisplayName("点踩：必须带白名单内的原因")
    void dislikeNeedsKnownReason() {
        FeedbackService.Validated ok = FeedbackService.validate(-1, "答非所问", "问的是 A 答的却是 B");
        assertEquals(-1, ok.rating());
        assertEquals("答非所问", ok.reason());
        assertEquals("问的是 A 答的却是 B", ok.comment());

        assertThrows(IllegalArgumentException.class, () -> FeedbackService.validate(-1, null, null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackService.validate(-1, "  ", null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackService.validate(-1, "不好用", null));
    }

    @Test
    @DisplayName("点赞：原因一律丢弃（赞不该出现在差评原因分布里）")
    void likeDropsReason() {
        FeedbackService.Validated ok = FeedbackService.validate(1, "内容不全", null);
        assertEquals(1, ok.rating());
        assertNull(ok.reason());
    }

    @Test
    @DisplayName("评价只能是 ±1：0、2、null 都不接受")
    void ratingIsStrict() {
        for (Integer rating : new Integer[] {0, 2, -2, null}) {
            assertThrows(IllegalArgumentException.class, () -> FeedbackService.validate(rating, "其他", null));
        }
    }

    @Test
    @DisplayName("评论去空白、超长拒绝")
    void commentIsTrimmedAndBounded() {
        assertNull(FeedbackService.validate(1, null, "   ").comment());
        assertEquals("说明", FeedbackService.validate(1, null, "  说明  ").comment());
        String tooLong = "长".repeat(FeedbackService.MAX_COMMENT + 1);
        assertThrows(IllegalArgumentException.class, () -> FeedbackService.validate(1, null, tooLong));
    }

    @Test
    @DisplayName("原因清单是固定枚举：前端 chips 与后端白名单必须同源")
    void reasonsAreWhitelist() {
        assertEquals(5, FeedbackService.REASONS.size());
        assertTrue(FeedbackService.REASONS.contains("其他"), "要给都不适用的那些情况留出口：" + FeedbackService.REASONS);
    }
}
