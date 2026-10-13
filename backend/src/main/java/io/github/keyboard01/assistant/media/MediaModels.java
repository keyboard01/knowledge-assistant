package io.github.keyboard01.assistant.media;

import java.util.List;

/** 图片素材的对外模型。 */
public final class MediaModels {

    private MediaModels() {
    }

    /** 落库用的一张资产（含两套向量：文本 embedding 与可选的视觉向量）。 */
    public record AssetInput(int assetIndex, Integer page, String ext, int width, int height, long byteSize,
                             String contentHash, String caption, String ocrText, String searchText,
                             String embedModel, float[] embedding,
                             String visionEmbedModel, float[] visionEmbedding) {
    }

    /** 从库里读回的一张资产。 */
    public record Asset(long id, long documentId, int assetIndex, Integer page, String ext, int width, int height,
                        long byteSize, String contentHash, String caption, String ocrText, String searchText,
                        String embedModel, String visionEmbedModel) {
    }

    /** 一条检索命中（带出处）。 */
    public record Hit(long assetId, long documentId, String docNo, String title, String version, Integer page,
                      String ext, String caption, double score) {
    }

    public record SearchResult(List<Hit> hits, String notice) {
    }
}
