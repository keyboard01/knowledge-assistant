package io.github.keyboard01.assistant.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.keyboard01.assistant.adapters.embedding.TextEmbedder;

/**
 * 图片索引的幂等与跳过：内容/模型没变就不该再调 VLM 与向量化（那是这条链路里最贵的两步）。
 * 手写 fake，不起 Spring 上下文（沿用本仓库测试风格）。
 */
class MediaIndexerTest {

    private static final int DIM = 1024;
    private static final String MODEL = "fake-embed";

    private static final class FakeCaptioner implements VisionCaptioner {
        int calls;

        @Override
        public boolean enabled(Map<String, String> cfg) {
            return true;
        }

        @Override
        public Caption caption(byte[] imageBytes, String mimeType, Map<String, String> cfg) {
            calls++;
            return new Caption("一张结构示意图", "图1");
        }
    }

    private static final class FakeEmbedder implements TextEmbedder {
        int calls;

        @Override
        public List<float[]> embed(List<String> texts) {
            calls++;
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[DIM]);
            }
            return out;
        }

        @Override
        public String model() {
            return MODEL;
        }

        @Override
        public int dimensions() {
            return DIM;
        }
    }

    /** 视觉向量默认关闭：本测试聚焦"文本侧幂等"，视觉链路单独由 ImageAssetRepositoryTest / embedder 测试覆盖。 */
    private static final class FakeVision implements ImageEmbedder {
        @Override
        public boolean enabled(Map<String, String> cfg) {
            return false;
        }

        @Override
        public String model(Map<String, String> cfg) {
            return "none";
        }

        @Override
        public float[] embedImage(byte[] imageBytes, String mimeType, Map<String, String> cfg) {
            throw new AssertionError("未启用时不该被调用");
        }

        @Override
        public float[] embedText(String text, Map<String, String> cfg) {
            throw new AssertionError("未启用时不该被调用");
        }
    }

    private static final class FakeRepo extends ImageAssetRepository {
        final Map<Integer, String> fingerprints = new HashMap<>();
        final List<MediaModels.AssetInput> upserted = new ArrayList<>();
        int deletedFrom = -1;

        FakeRepo() {
            super(null);
        }

        @Override
        public Map<Integer, String> fingerprints(long documentId) {
            return fingerprints;
        }

        @Override
        public void upsert(long documentId, List<MediaModels.AssetInput> assets) {
            upserted.addAll(assets);
        }

        @Override
        public int deleteFrom(long documentId, int fromIndex) {
            deletedFrom = fromIndex;
            return 0;
        }
    }

    private static final class FakeStore extends AssetStore {
        FakeStore() {
            super(null);
        }

        @Override
        public Path assetPath(String contentHash, String ext) {
            return Path.of("target", "test-assets", contentHash + "." + ext);
        }

        @Override
        public Path thumbPath(String contentHash) {
            return Path.of("target", "test-assets", contentHash + ".thumb.jpg");
        }

        @Override
        public void writeIfAbsent(Path path, byte[] bytes) {
            // 测试不落盘
        }
    }

    @Test
    @DisplayName("内容与模型都没变 → 不再调用 VLM 与向量化")
    void reusesUnchangedAssets(@TempDir Path dir) throws Exception {
        Path pdf = pdfWithOneImage(dir);
        FakeCaptioner captioner = new FakeCaptioner();
        FakeEmbedder embedder = new FakeEmbedder();
        FakeRepo repo = new FakeRepo();
        MediaIndexer indexer = new MediaIndexer(captioner, new FakeVision(), repo, embedder, new FakeStore());

        MediaIndexer.IndexResult first = indexer.index(1L, pdf, DocKind.TEXT, false, Map.of(), Map.of());
        assertEquals(1, first.assetCount());
        assertEquals(1, captioner.calls);
        assertEquals(1, embedder.calls);
        assertEquals(1, repo.upserted.size());
        String hash = repo.upserted.get(0).contentHash();

        // 把指纹设成"与当前一致"，第二次应完全跳过昂贵的调用
        repo.fingerprints.put(0, ImageAssetRepository.fingerprint(hash, MODEL, null));
        captioner.calls = 0;
        embedder.calls = 0;
        repo.upserted.clear();

        MediaIndexer.IndexResult second = indexer.index(1L, pdf, DocKind.TEXT, false, Map.of(), Map.of());
        assertEquals(1, second.assetCount());
        assertEquals(0, captioner.calls, "内容未变不应再调 VLM");
        assertEquals(0, embedder.calls, "内容未变不应再向量化");
        assertTrue(repo.upserted.isEmpty());
        assertEquals(1, repo.deletedFrom);
    }

    @Test
    @DisplayName("未启用视觉模型 → 直接跳过，不抽也不描述")
    void skippedWhenVisionDisabled(@TempDir Path dir) throws Exception {
        Path pdf = pdfWithOneImage(dir);
        VisionCaptioner disabled = new VisionCaptioner() {
            @Override
            public boolean enabled(Map<String, String> cfg) {
                return false;
            }

            @Override
            public Caption caption(byte[] imageBytes, String mimeType, Map<String, String> cfg) {
                throw new AssertionError("未启用时不该被调用");
            }
        };
        MediaIndexer indexer = new MediaIndexer(disabled, new FakeVision(), new FakeRepo(), new FakeEmbedder(),
                new FakeStore());

        MediaIndexer.IndexResult result = indexer.index(1L, pdf, DocKind.TEXT, false, Map.of(), Map.of());
        assertTrue(result.skipped());
        assertEquals(0, result.assetCount());
    }

    private static Path pdfWithOneImage(Path dir) throws Exception {
        BufferedImage bitmap = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bitmap.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 100, 80);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bitmap, "png", out);

        Path pdf = dir.resolve("one.pdf");
        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, out.toByteArray(), "img");
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image, 10, 10, 100, 80);
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
