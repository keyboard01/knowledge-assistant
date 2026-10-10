package com.hykj.assistant.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析入口（P1 解析升级）：先试版面分析服务，拿不到就回落内置 PDFBox/POI 解析器。
 *
 * <p>把"用哪个解析器"收在一处：调用方（入库、解析预览）只说"给我块"，不关心走的是哪条路，
 * 但结果里会带上 {@link Result#parser()} 说明**实际用了哪个**，供解析预览让人确认。</p>
 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    /** 解析结果 + 实际使用的解析器名字。 */
    public record Result(List<ParsedBlock> blocks, String parser) {
    }

    private final LayoutParser layoutParser;

    public DocumentParseService(LayoutParser layoutParser) {
        this.layoutParser = layoutParser;
    }

    /** 内置支持 PDF/DOCX；开了版面分析就再宽一点（具体格式由服务决定，失败照样回落）。 */
    public boolean supports(Path file) {
        return DocumentParser.supports(file) || layoutParser.enabled();
    }

    public Result parse(Path file) throws IOException, InterruptedException {
        if (layoutParser.enabled()) {
            try {
                Optional<List<ParsedBlock>> blocks = layoutParser.parse(file);
                if (blocks.isPresent() && !blocks.get().isEmpty()) {
                    return new Result(blocks.get(), layoutParser.displayName());
                }
                log.debug("版面分析未返回内容，回落内置解析器：{}", file.getFileName());
            } catch (InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("版面分析触发失败，回落内置解析器：{} —— {}", file.getFileName(), ex.toString());
            }
        }
        return new Result(DocumentParser.parse(file), "内置解析器（PDFBox/POI）");
    }
}
