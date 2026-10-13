package io.github.keyboard01.assistant.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 图片抽取：PDFBox 内嵌图抽取、跨页去重、直接上传的图片。 */
class ImageExtractorTest {

    @Test
    @DisplayName("PDF：抽出内嵌图，同一张图被多页引用时只算一次")
    void extractsPdfImageWithDedupe(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("sample.pdf");
        byte[] imageBytes = png(120, 90);
        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, imageBytes, "img");
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.drawImage(image, 20, 20, 120, 90);
                }
            }
            document.save(pdf.toFile());
        }

        List<ExtractedImage> extracted = new ArrayList<>();
        ImageExtractor.extract(pdf, DocKind.TEXT, ImageExtractor.Limits.DEFAULT, extracted::add);

        assertEquals(1, extracted.size(), "同一张图被多页引用时只应抽一次");
        assertEquals(0, extracted.get(0).assetIndex());
        assertTrue(extracted.get(0).width() > 0 && extracted.get(0).height() > 0);
        assertNotNull(extracted.get(0).contentHash());
    }

    @Test
    @DisplayName("直接上传的图片：整文件即一张资产，无页码")
    void singleImageFile(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("pic.png");
        Files.write(png, png(64, 48));

        List<ExtractedImage> extracted = new ArrayList<>();
        ImageExtractor.extract(png, DocKind.IMAGE, ImageExtractor.Limits.DEFAULT, extracted::add);

        assertEquals(1, extracted.size());
        assertNull(extracted.get(0).page());
        assertEquals("png", extracted.get(0).ext());
    }

    @Test
    @DisplayName("PPTX：抽出幻灯片里的图片，页码 = 幻灯片序号")
    void extractsPptxImage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (XMLSlideShow deck = new XMLSlideShow()) {
            deck.createSlide();
            XSLFSlide second = deck.createSlide();
            XSLFPictureData picture = deck.addPicture(png(80, 60), PictureData.PictureType.PNG);
            second.createPicture(picture);
            deck.write(Files.newOutputStream(file));
        }

        List<ExtractedImage> extracted = new ArrayList<>();
        ImageExtractor.extract(file, DocKind.TEXT, ImageExtractor.Limits.DEFAULT, extracted::add);

        assertEquals(1, extracted.size());
        assertEquals(2, extracted.get(0).page());
        assertEquals("png", extracted.get(0).ext());
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
