package io.github.keyboard01.assistant.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/**
 * 从文档里抽取内嵌图片（PDF 用 PDFBox 2.x，DOCX 用 POI）。**流式**交给 {@link ImageSink}。
 *
 * <p>几个必须处理的现实问题：</p>
 * <ul>
 *   <li>PDF 的图常藏在 **Form XObject** 里（Word/Acrobat 导出），只扫页面顶层会漏一大半 → 递归。</li>
 *   <li>同一张图常按对象被多页引用（页眉 logo）→ 按 **COS 对象身份**去重，否则每页都重复描述一次。</li>
 *   <li>遮罩（Mask/SoftMask）不是内容图 → 收集成 skip-set 跳过；模板（stencil）直接跳过。</li>
 *   <li>大位图 `getImage()` 会把整张展开成内存 → 按宽高/字节上限过滤；JPX 等解不了的就跳过，不让整份文档失败。</li>
 *   <li>DOCX 的 `getAllPictures()` 含页眉页脚与 EMF/WMF → 只留光栅格式，按内容哈希去重。</li>
 * </ul>
 */
public final class ImageExtractor {

    public record Limits(int maxImages, int maxDimension, long maxBytes) {

        public static final Limits DEFAULT = new Limits(60, 4000, 8L * 1024 * 1024);

        public Limits {
            maxImages = Math.max(1, maxImages);
            maxDimension = Math.max(64, maxDimension);
            maxBytes = Math.max(64 * 1024, maxBytes);
        }
    }

    private static final Set<String> RASTER = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private record Encoded(String ext, byte[] bytes) {
    }

    private ImageExtractor() {
    }

    public static void extract(Path file, DocKind kind, Limits limits, ImageSink sink) throws IOException {
        if (kind == DocKind.IMAGE) {
            extractSingleImage(file, limits, sink);
            return;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            extractPdf(file, limits, sink);
        } else if (name.endsWith(".docx")) {
            extractDocx(file, limits, sink);
        } else if (name.endsWith(".pptx")) {
            extractPptx(file, limits, sink);
        }
        // 其它格式暂时抽不出图：不报错，交给上层记为"无资产"
    }

    /** 直接上传的图片：整文件就是一张资产。 */
    private static void extractSingleImage(Path file, Limits limits, ImageSink sink) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0 || bytes.length > limits.maxBytes()) {
            return;
        }
        int[] dim = dimensions(bytes);
        if (dim[0] > limits.maxDimension() || dim[1] > limits.maxDimension()) {
            return;
        }
        String ext = DocKind.extension(file.getFileName().toString());
        sink.accept(new ExtractedImage(0, null, ext.isEmpty() ? "png" : ext, dim[0], dim[1], sha256(bytes), bytes));
    }

    private static void extractPdf(Path file, Limits limits, ImageSink sink) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            Set<COSStream> masks = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<COSStream> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
            int index = 0;
            int pageNo = 0;
            for (PDPage page : document.getPages()) {
                pageNo++;
                List<PDImageXObject> images = new ArrayList<>();
                Set<COSStream> visitedForms = Collections.newSetFromMap(new IdentityHashMap<>());
                collectImages(page.getResources(), images, masks, visitedForms);
                for (PDImageXObject image : images) {
                    if (index >= limits.maxImages()) {
                        return;
                    }
                    COSStream stream = image.getCOSObject();
                    if (masks.contains(stream) || emitted.contains(stream) || image.isStencil()) {
                        continue;
                    }
                    if (image.getWidth() > limits.maxDimension() || image.getHeight() > limits.maxDimension()) {
                        continue;
                    }
                    Encoded encoded = decode(image);
                    if (encoded == null || encoded.bytes().length == 0 || encoded.bytes().length > limits.maxBytes()) {
                        continue;
                    }
                    emitted.add(stream);
                    sink.accept(new ExtractedImage(index++, pageNo, encoded.ext(), image.getWidth(),
                            image.getHeight(), sha256(encoded.bytes()), encoded.bytes()));
                }
            }
        }
    }

    private static void collectImages(PDResources resources, List<PDImageXObject> images, Set<COSStream> masks,
                                      Set<COSStream> visitedForms) {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object;
            try {
                object = resources.getXObject(name);
            } catch (IOException ex) {
                continue; // 单个对象坏了不影响整页
            }
            if (object instanceof PDFormXObject form) {
                COSStream stream = form.getCOSObject();
                if (visitedForms.add(stream)) {
                    collectImages(form.getResources(), images, masks, visitedForms);
                }
            } else if (object instanceof PDImageXObject image) {
                images.add(image);
                try {
                    addMask(image.getMask(), masks);
                    addMask(image.getSoftMask(), masks);
                } catch (IOException ex) {
                    // 遮罩解析失败不影响把这张图当内容图抽出：忽略即可
                }
            }
        }
    }

    private static void addMask(PDImageXObject mask, Set<COSStream> masks) {
        if (mask != null) {
            masks.add(mask.getCOSObject());
        }
    }

    /** 解成 PNG（带 alpha）或 JPEG（不带）；解不了返回 null。 */
    private static Encoded decode(PDImageXObject image) {
        try {
            BufferedImage bitmap = image.getImage();
            if (bitmap == null) {
                return null;
            }
            boolean alpha = bitmap.getColorModel() != null && bitmap.getColorModel().hasAlpha();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(bitmap, alpha ? "png" : "jpg", out)) {
                return null;
            }
            return new Encoded(alpha ? "png" : "jpg", out.toByteArray());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static void extractDocx(Path file, Limits limits, ImageSink sink) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            Set<String> seen = new HashSet<>();
            int index = 0;
            for (XWPFPictureData picture : document.getAllPictures()) {
                if (index >= limits.maxImages()) {
                    return;
                }
                String ext = picture.suggestFileExtension();
                if (ext == null || !RASTER.contains(ext.toLowerCase(Locale.ROOT))) {
                    continue; // EMF/WMF 等：无头 JVM 解不了，直接跳过
                }
                byte[] bytes = picture.getData();
                if (bytes == null || bytes.length == 0 || bytes.length > limits.maxBytes()) {
                    continue;
                }
                String hash = sha256(bytes);
                if (!seen.add(hash)) {
                    continue;
                }
                int[] dim = dimensions(bytes);
                if (dim[0] > limits.maxDimension() || dim[1] > limits.maxDimension()) {
                    continue;
                }
                sink.accept(new ExtractedImage(index++, null,
                        "jpeg".equalsIgnoreCase(ext) ? "jpg" : ext.toLowerCase(Locale.ROOT),
                        dim[0], dim[1], hash, bytes));
            }
        }
    }

    /** PPTX：按幻灯片取图片形状，`page` = 幻灯片序号；按内容哈希去重。 */
    private static void extractPptx(Path file, Limits limits, ImageSink sink) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(Files.newInputStream(file))) {
            Set<String> seen = new HashSet<>();
            int index = 0;
            int page = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                page++;
                for (XSLFShape shape : slide.getShapes()) {
                    if (index >= limits.maxImages()) {
                        return;
                    }
                    if (!(shape instanceof XSLFPictureShape pictureShape)) {
                        continue;
                    }
                    XSLFPictureData picture = pictureShape.getPictureData();
                    if (picture == null) {
                        continue;
                    }
                    String ext = normaliseExt(picture.getFileName());
                    if (!RASTER.contains(ext)) {
                        continue; // EMF/WMF 等：无头 JVM 解不了
                    }
                    byte[] bytes = picture.getData();
                    if (bytes == null || bytes.length == 0 || bytes.length > limits.maxBytes()) {
                        continue;
                    }
                    String hash = sha256(bytes);
                    if (!seen.add(hash)) {
                        continue;
                    }
                    int[] dim = dimensions(bytes);
                    if (dim[0] > limits.maxDimension() || dim[1] > limits.maxDimension()) {
                        continue;
                    }
                    sink.accept(new ExtractedImage(index++, page, ext, dim[0], dim[1], hash, bytes));
                }
            }
        }
    }

    private static String normaliseExt(String fileName) {
        String ext = DocKind.extension(fileName);
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    private static int[] dimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return new int[] {0, 0};
            }
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return new int[] {0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            return new int[] {0, 0};
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
