package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.util.Map;

/**
 * 跨模态图片向量：图片与**查询文本**由同一个多模态模型编码进同一向量空间，
 * 从而支持"用一句话找图"（而不只是匹配图片的文字描述）。
 *
 * <p>与 `TextEmbedder` 是两套独立空间，落库落在不同的列上（`vision_embedding` vs `embedding`）。</p>
 *
 * <p>与其它适配器同一约定：实现类不依赖配置层，配置由调用方取好、以 `cfg` 传入。</p>
 */
public interface ImageEmbedder {

    /**
     * @param cfg 「图片视觉向量」分组的有效配置
     * @return 未启用（开关关闭或配置不全）返回 false——此时不建视觉向量，也不走视觉召回。
     */
    boolean enabled(Map<String, String> cfg);

    /** 当前模型名，写进 `image_asset.vision_embed_model`（换模型可识别、可迁移）。 */
    String model(Map<String, String> cfg);

    float[] embedImage(byte[] imageBytes, String mimeType, Map<String, String> cfg) throws IOException, InterruptedException;

    float[] embedText(String text, Map<String, String> cfg) throws IOException, InterruptedException;
}
