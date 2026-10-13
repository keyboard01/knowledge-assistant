package io.github.keyboard01.assistant.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文档类型。`TEXT` = 走解析→chunk→内核的文本文档（PDF/DOCX）；
 * `IMAGE` = 直接上传的图片（不入内核、不产 chunk，只产出图片资产）。
 */
public enum DocKind {

    TEXT,
    IMAGE;

    /** 直接上传时按扩展名认。只收 ImageIO 能解的格式（webp 需要插件，不收）。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    public static boolean isImageExtension(String ext) {
        return ext != null && IMAGE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    public static Set<String> imageExtensions() {
        return IMAGE_EXTENSIONS;
    }

    public static DocKind of(Path file) {
        return ofName(file == null ? "" : file.getFileName().toString());
    }

    public static DocKind ofName(String fileName) {
        return isImageExtension(extension(fileName)) ? IMAGE : TEXT;
    }

    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
