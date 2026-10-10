package io.github.keyboard01.assistant.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.embedding.TextEmbedder;
import io.github.keyboard01.assistant.parse.ChunkSplitter;
import io.github.keyboard01.assistant.parse.DocumentParseService;
import io.github.keyboard01.assistant.parse.ParsedBlock;
import io.github.keyboard01.assistant.parse.ParsedChunk;

/**
 * 自建片段流水线：解析 → 切分 →（可选）上下文增强 → 向量化 → 落库（S0.2 / P1）。
 *
 * <p>幂等靠"片段指纹序列"（{@code content_hash:context_hash}）：解析结果与上下文都没变，
 * 就一条 SQL 都不写、一次 embedding 都不调。这不只是省钱——重复向量化会产生一批新 id，
 * 引用里的 chunkId 就全断了。</p>
 *
 * <p>写库一律走 {@code upsert}（按 {@code (document_id, chunk_index)} 覆盖），不做"整份删了重灌"：
 * 即使 {@code force=true} 重算，同一位置的片段 id 也不变，历史账本的 chunk 引用不会因此悬空。</p>
 *
 * <p>上下文增强（P1）默认关闭：开启后给每个片段补一句上下文摘要，**随片段一起向量化**
 * （展示与关键词仍用正文），关闭时指纹与引入该能力前逐位等价，不会触发全量重嵌。</p>
 */
@Service
public class ChunkIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexer.class);
    /** DDL 里 embedding 是 vector(1024)：维度对不上必须当场报错，不能写进库才发现。 */
    private static final int EXPECTED_DIMENSIONS = 1024;

    public record IndexResult(long documentId, String action, int chunks, String detail) {
    }

    private final DocumentChunkRepository chunks;
    private final TextEmbedder embedder;
    private final Contextualizer contextualizer;
    private final DocumentParseService parser;

    public ChunkIndexer(DocumentChunkRepository chunks, TextEmbedder embedder, Contextualizer contextualizer,
                        DocumentParseService parser) {
        this.chunks = chunks;
        this.embedder = embedder;
        this.contextualizer = contextualizer;
        this.parser = parser;
    }

    public IndexResult index(long documentId, String documentTitle, Path file, boolean force)
            throws IOException, InterruptedException {
        if (!parser.supports(file)) {
            return new IndexResult(documentId, "SKIPPED", 0, "该格式暂不支持自建解析（只做 PDF / DOCX）");
        }
        List<ParsedBlock> blocks = parser.parse(file).blocks();
        List<ParsedChunk> parsed = ChunkSplitter.split(blocks);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("解析结果为空，拒绝用空结果覆盖已有片段（检查文件是否损坏）");
        }

        // 上下文增强（默认关闭）：拿不到就整体退回无上下文，绝不半信半疑地只补一半。
        List<String> contexts = contextualizer.enabled()
                ? contextualizer.contexts(documentTitle, parsed)
                : blankContexts(parsed.size());
        if (contexts.size() != parsed.size()) {
            contexts = blankContexts(parsed.size());
        }

        List<DocumentChunkRepository.IndexedChunk> indexed = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            ParsedChunk chunk = parsed.get(i);
            String context = contexts.get(i) == null ? "" : contexts.get(i);
            String contentHash = IngestService.sha256(chunk.content().getBytes(StandardCharsets.UTF_8));
            String contextHash = context.isBlank() ? null
                    : IngestService.sha256(context.getBytes(StandardCharsets.UTF_8));
            indexed.add(new DocumentChunkRepository.IndexedChunk(chunk, contentHash, context, contextHash));
        }
        List<String> fingerprints = indexed.stream().map(DocumentChunkRepository::fingerprint).toList();
        if (!force && fingerprints.equals(chunks.fingerprints(documentId))) {
            return new IndexResult(documentId, "SKIPPED", parsed.size(), "片段内容未变化，跳过重复向量化");
        }

        List<float[]> vectors = embedder.embed(indexed.stream().map(ChunkIndexer::embedInput).toList());
        if (vectors.size() != parsed.size()) {
            throw new IllegalStateException("向量条数与片段数不一致：" + vectors.size() + "/" + parsed.size());
        }
        int dimensions = vectors.get(0).length;
        if (dimensions != EXPECTED_DIMENSIONS) {
            throw new IllegalStateException("向量维度 " + dimensions + " 与库里的 vector(" + EXPECTED_DIMENSIONS
                    + ") 不一致：换 embedding 模型要走迁移，不能直接写");
        }
        // 先覆盖写、再删尾部：顺序反过来的话，中间那一刻库里是空的（且尾部行会误判成"已存在"）。
        chunks.upsert(documentId, indexed, vectors, embedder.model());
        int removed = chunks.deleteFrom(documentId, parsed.size());
        long withPage = parsed.stream().filter(chunk -> chunk.pageFrom() != null).count();
        long withContext = contexts.stream().filter(context -> !context.isBlank()).count();
        String detail = "片段 " + parsed.size() + " 条（其中带页码 " + withPage + " 条"
                + (withContext > 0 ? "，带上下文 " + withContext + " 条" : "") + "），模型 " + embedder.model()
                + (removed > 0 ? "，清理尾部多余片段 " + removed + " 条" : "");
        log.info("[片段] 文档 {} {}（块 {} → 片段 {}）", documentId, detail, blocks.size(), parsed.size());
        return new IndexResult(documentId, "INDEXED", parsed.size(), detail);
    }

    /** 向量化输入：有上下文就前置（脱离原文也能被检索到），否则就是章节路径 + 正文。 */
    private static String embedInput(DocumentChunkRepository.IndexedChunk item) {
        String context = item.contextPrefix();
        String body = item.chunk().embedText();
        return context == null || context.isBlank() ? body : context + "\n" + body;
    }

    private static List<String> blankContexts(int size) {
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add("");
        }
        return values;
    }
}
