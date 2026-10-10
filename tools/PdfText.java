import java.io.File;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 从 PDF 里把文字提出来，给验收脚本当判据用（AC-D-008 明确要求"用 PDFBox 提取文本核对，不看截图"）。
 *
 * <p>为什么要"压平"空白：水印是 45° 旋转铺的，PDFTextStripper 会把旋转文本切成一堆短行，
 * 直接 contains("admin 2026-10-08") 永远为假——那是判据写错了，不是水印没烧上。
 * 所以两边都去掉空白再比对。</p>
 *
 * <p>用法：java -cp &lt;pdfbox;fontbox;commons-logging&gt; tools/PdfText.java &lt;file.pdf&gt; ["要查的串"]</p>
 */
public class PdfText {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法：PdfText <file.pdf> [要查的串]");
            System.exit(2);
        }
        try (PDDocument document = PDDocument.load(new File(args[0]))) {
            String text = new PDFTextStripper().getText(document);
            String flat = flatten(text);
            System.out.println("PAGES=" + document.getNumberOfPages());
            System.out.println("CHARS=" + flat.length());
            if (args.length > 1) {
                System.out.println("HAS_TOKEN=" + flat.contains(flatten(args[1])));
            }
            String sample = flat.length() > 300 ? flat.substring(0, 300) : flat;
            System.out.println("SAMPLE=" + sample);
        }
    }

    private static String flatten(String value) {
        StringBuilder builder = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}