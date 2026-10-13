package io.github.keyboard01.assistant.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 解析入口：按扩展名分发。支持 PDF / DOCX / PPTX——这三种是首批语料与实际文档的主力，
 * 其余格式宁可明确报"不支持"，也不要用一个半吊子解析器产出脏块（脏块比没块更难查）。
 *
 * <p>老 {@code .ppt}（二进制）不支持：其 HSLF 依赖 {@code poi-scratchpad}，本仓库离线无该 jar。</p>
 */
public final class DocumentParser {

    private DocumentParser() {
    }

    public static boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".pptx");
    }

    public static List<ParsedBlock> parse(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return PdfBlockParser.parse(file);
        }
        if (name.endsWith(".docx")) {
            return DocxBlockParser.parse(file);
        }
        if (name.endsWith(".pptx")) {
            return PptxBlockParser.parse(file);
        }
        int dot = name.lastIndexOf('.');
        throw new IOException("暂不支持解析该格式：" + (dot < 0 ? name : name.substring(dot)));
    }
}