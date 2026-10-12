package io.github.keyboard01.assistant.ingest;

import java.io.IOException;
import java.util.List;

import io.github.keyboard01.assistant.parse.ParsedChunk;

/**
 * 片段上下文增强的内部契约（P1 Contextual Retrieval）。
 *
 * <p>片段被切得很细（默认预算约 200 token，见 {@code assistant.chunking.*}），召回精度是高了，
 * 但脱离上下文后语义不完整——"一线城市指北京、上海、广州、深圳"这种定义句一旦离开它所在的
 * 章节，模型和重排都容易看走眼。这个接口在**入库时**给每个片段补一句"文档标题 > 章节路径"级的
 * 上下文摘要，随片段一起向量化。</p>
 *
 * <p>默认关闭（{@code 文档同步.contextual}）；失败要 fail-open——拿不到上下文就按原样入库，
 * 绝不因为一句摘要生成不出来就把整份文档的片段卡住。</p>
 */
public interface Contextualizer {

    /** 是否启用。关闭时入库不生成任何上下文，与引入该能力前逐位一致。 */
    boolean enabled();

    /**
     * 给一批片段各生成一句上下文摘要，返回顺序与入参一一对应；某段生成不出来用空串占位。
     * 只有中断会往外抛（其余失败都在实现内降级为空串）。
     */
    List<String> contexts(String documentTitle, List<ParsedChunk> chunks)
            throws IOException, InterruptedException;

    default String displayName() {
        return getClass().getSimpleName();
    }
}
