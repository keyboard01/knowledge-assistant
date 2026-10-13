package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.embedding.TextEmbedder;

/**
 * 图片资产流水线：抽取 → 落盘 → （VLM 描述 + 取字）→ 向量化 → 入库。
 *
 * <p>与 `ChunkIndexer` 平行，四条不变量一致：</p>
 * <ul>
 *   <li><b>幂等按行</b>：`内容:文本模型:视觉模型` 未变就跳过该图的 VLM 与向量化（VLM 与多模态模型才是贵的），
 *       靠 `(document_id, asset_index)` upsert 覆盖、尾部删除；</li>
 *   <li><b>失败隔离</b>：本方法抛异常由调用方记为 `asset_status='FAILED'`，**绝不让文档入库失败**；</li>
 *   <li><b>维度守卫</b>：文本向量与视觉向量都必须等于库内固定维度，否则提前报错而不是写坏库；</li>
 *   <li><b>两套空间各存一列</b>：`embedding`（文本模型）与 `vision_embedding`（多模态模型），不混用。</li>
 * </ul>
 */
@Service
public class MediaIndexer {

    private static final Logger log = LoggerFactory.getLogger(MediaIndexer.class);

    /** 与 image_asset.embedding / vision_embedding 的 DDL 维度一致。 */
    private static final int EXPECTED_DIMENSIONS = 1024;
    private static final int THUMB_MAX = 320;

    public record IndexResult(int assetCount, boolean skipped, String detail) {

        public static IndexResult skipped(String reason) {
            return new IndexResult(0, true, reason);
        }

        public static IndexResult indexed(int count, String detail) {
            return new IndexResult(count, false, detail);
        }
    }

    /** 待入库的一张：向量算齐后一起写。 */
    private record Pending(int assetIndex, ExtractedImage image, VisionCaptioner.Caption caption, String searchText,
                           float[] visionVector) {
    }

    private final VisionCaptioner visionCaptioner;
    private final ImageEmbedder imageEmbedder;
    private final ImageAssetRepository assets;
    private final TextEmbedder embedder;
    private final AssetStore store;

    public MediaIndexer(VisionCaptioner visionCaptioner, ImageEmbedder imageEmbedder, ImageAssetRepository assets,
                        TextEmbedder embedder, AssetStore store) {
        this.visionCaptioner = visionCaptioner;
        this.imageEmbedder = imageEmbedder;
        this.assets = assets;
        this.embedder = embedder;
        this.store = store;
    }

    public IndexResult index(long documentId, Path file, DocKind kind, boolean force,
                             Map<String, String> visionCfg, Map<String, String> imageCfg)
            throws IOException, InterruptedException {
        if (!visionCaptioner.enabled(visionCfg)) {
            return IndexResult.skipped("未启用图片理解（系统配置 → 图片理解）");
        }
        Map<Integer, String> existing = assets.fingerprints(documentId);
        String textModel = embedder.model();
        boolean visionEnabled = imageEmbedder.enabled(imageCfg);
        String visionModel = visionEnabled ? imageEmbedder.model(imageCfg) : null;
        List<Pending> pending = new ArrayList<>();
        int[] counter = {0};

        try {
            ImageExtractor.extract(file, kind, ImageExtractor.Limits.DEFAULT, image -> {
                int assetIndex = counter[0]++;
                store.writeIfAbsent(store.assetPath(image.contentHash(), image.ext()), image.bytes());
                store.writeIfAbsent(store.thumbPath(image.contentHash()),
                        ImageThumbs.downscale(image.bytes(), THUMB_MAX, "jpg"));
                String fingerprint = ImageAssetRepository.fingerprint(image.contentHash(), textModel, visionModel);
                if (!force && fingerprint.equals(existing.get(assetIndex))) {
                    return; // 内容与模型都没变：跳过 VLM 与向量化
                }
                VisionCaptioner.Caption caption = caption(image, visionCfg);
                float[] visionVector = visionEnabled ? vision(image, imageCfg) : null;
                pending.add(new Pending(assetIndex, image, caption, buildSearchText(caption), visionVector));
            });
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("图片抽取/描述被中断");
            }
            throw ex;
        }

        int extracted = counter[0];
        if (!pending.isEmpty()) {
            List<float[]> textVectors = embedder.embed(pending.stream().map(Pending::searchText).toList());
            if (textVectors.size() != pending.size()) {
                throw new IllegalStateException(
                        "向量化返回条数与图片数不一致：" + textVectors.size() + "/" + pending.size());
            }
            checkDimensions(textVectors.get(0).length, "文本向量");
            List<MediaModels.AssetInput> inputs = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                Pending item = pending.get(i);
                float[] visionVector = item.visionVector();
                if (visionVector != null) {
                    checkDimensions(visionVector.length, "视觉向量");
                }
                inputs.add(new MediaModels.AssetInput(item.assetIndex(), item.image().page(), item.image().ext(),
                        item.image().width(), item.image().height(), item.image().bytes().length,
                        item.image().contentHash(), item.caption().caption(), item.caption().ocr(),
                        item.searchText(), textModel, textVectors.get(i), visionModel, visionVector));
            }
            assets.upsert(documentId, inputs);
        }
        assets.deleteFrom(documentId, extracted);
        if (extracted == 0) {
            return IndexResult.indexed(0, "文档内没有可用图片");
        }
        log.info("图片索引完成：document={} 共 {} 张，重算 {} 张，视觉向量={}", documentId, extracted, pending.size(),
                visionEnabled ? "开" : "关");
        return IndexResult.indexed(extracted, "图片 " + extracted + " 张（重算 " + pending.size() + " 张）");
    }

    private VisionCaptioner.Caption caption(ExtractedImage image, Map<String, String> visionCfg) throws IOException {
        try {
            return visionCaptioner.caption(image.bytes(), mimeOf(image.ext()), visionCfg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("图片理解被中断", ex);
        }
    }

    private float[] vision(ExtractedImage image, Map<String, String> imageCfg) throws IOException {
        try {
            return imageEmbedder.embedImage(image.bytes(), mimeOf(image.ext()), imageCfg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("图片视觉向量被中断", ex);
        }
    }

    private static void checkDimensions(int dimensions, String what) {
        if (dimensions != EXPECTED_DIMENSIONS) {
            throw new IllegalStateException(what + "维度 " + dimensions + " 与库内固定维度 "
                    + EXPECTED_DIMENSIONS + " 不一致：请确认模型配置");
        }
    }

    private static String buildSearchText(VisionCaptioner.Caption caption) {
        String text = ((caption.caption() == null ? "" : caption.caption()) + "\n"
                + (caption.ocr() == null ? "" : caption.ocr())).trim();
        // 全空时给个占位，避免"空字符串没法向量化"把一张图整没了
        return text.isEmpty() ? "图片" : text;
    }

    private static String mimeOf(String ext) {
        return "jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext)
                ? "image/jpeg" : "image/" + ext;
    }
}
