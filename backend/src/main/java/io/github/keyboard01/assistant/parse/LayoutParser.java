package io.github.keyboard01.assistant.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 版面分析解析器的内部契约（P1 解析升级）。
 *
 * <p>PDFBox/POI 是启发式的——复杂排版（双栏、扫描件、跨页表格）识别率没保证，而"解析错，后面全错"：
 * 切分再准、重排再好，都是给错误文本排序。这个接口把"更强的版面分析"做成可替换的一层
 * （Docling / MinerU / PP-StructureV3 任一，只要能对上 {@code {blocks:[…]}} 契约）。</p>
 *
 * <p>默认关闭，且**任何失败/不支持都返回 {@link Optional#empty()}**，由
 * {@link DocumentParseService} 回落到内置解析器——解析是入口，绝不能因为一个可选服务挂掉就入库不了。</p>
 */
public interface LayoutParser {

    /** 是否启用。关闭时解析完全走内置 PDFBox/POI，与引入该能力前逐位一致。 */
    boolean enabled();

    /**
     * 用外部版面分析服务把文件解析成块。拿不到就返回 {@code empty()}（调用方回落内置解析器）；
     * 只有中断会往外抛。
     */
    Optional<List<ParsedBlock>> parse(Path file) throws IOException, InterruptedException;

    default String displayName() {
        return getClass().getSimpleName();
    }
}
