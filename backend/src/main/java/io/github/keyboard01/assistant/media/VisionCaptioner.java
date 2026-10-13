package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.util.Map;

/** 图片描述/取字。V1 用一次 VLM 调用同时拿到 caption 与图内文字。 */
public interface VisionCaptioner {

    /** @param caption 图片内容的中文描述；{@param ocr} 图内文字（没有则空串） */
    record Caption(String caption, String ocr) {
    }

    /**
     * @param cfg 「图片理解」分组的有效配置（由调用方从 SettingService 取，适配器不反向依赖配置层）
     * @return 未启用（开关关闭或配置不全）返回 false——此时整条图片流水线跳过。
     */
    boolean enabled(Map<String, String> cfg);

    Caption caption(byte[] imageBytes, String mimeType, Map<String, String> cfg) throws IOException, InterruptedException;
}
