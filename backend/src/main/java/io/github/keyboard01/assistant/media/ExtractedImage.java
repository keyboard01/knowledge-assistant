package io.github.keyboard01.assistant.media;

/**
 * 从文档里抽出来的一张图片。
 *
 * @param assetIndex  文档内的稳定序号（0 起），落库唯一键 `(document_id, asset_index)` 的另一半
 * @param page        页码 / 幻灯片号；DOCX 与直接上传的图片没有页码语义，为 null
 * @param ext         图片格式（png / jpg / …）
 * @param contentHash 图片字节的 SHA-256，用作内容寻址文件名，也用于"没变就跳过 VLM"
 */
public record ExtractedImage(int assetIndex, Integer page, String ext, int width, int height, String contentHash,
                             byte[] bytes) {
}
