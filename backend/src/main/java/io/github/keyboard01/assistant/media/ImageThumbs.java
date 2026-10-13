package io.github.keyboard01.assistant.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/**
 * 缩略图 / 送模型前的降采样。失败一律**返回原字节**——降采样是省钱省带宽的优化，
 * 不该成为"这张图用不了"的理由。
 */
public final class ImageThumbs {

    private ImageThumbs() {
    }

    public static byte[] downscale(byte[] source, int maxDimension, String format) {
        if (source == null || source.length == 0) {
            return source;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(source)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return source;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return source;
            }
            double scale = Math.min(1.0d, (double) maxDimension / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            boolean png = "png".equalsIgnoreCase(format);
            BufferedImage target = new BufferedImage(targetWidth, targetHeight,
                    png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                if (!png) {
                    // JPEG 没有 alpha：先铺白底，否则透明区会变成黑块
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, targetWidth, targetHeight);
                }
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(target, format, out)) {
                return source;
            }
            return out.toByteArray();
        } catch (Exception ex) {
            return source;
        }
    }
}
