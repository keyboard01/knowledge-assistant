package com.hykj.assistant.retrieval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.hykj.assistant.adapters.embedding.TextEmbedder;
import com.hykj.assistant.ingest.DocumentChunkRepository;

/**
 * 自建混合召回（P2 的默认检索实现）：本地向量 + 本地关键词 → RRF 融合 →（可选）cross-encoder 重排。
 *
 * <p>为什么不让 AnythingLLM 继续当唯一召回源：它给的相似度是压扁过的
 * （拿片段自己的原文当查询，最高只有 0.572；该答的 0.44 与库外的 0.42 分不开），
 * 而"该不该作答"这条线必须在一个量纲上可比。自建之后，分数就是余弦相似度，尺度由我们定。</p>
 *
 * <p>内核通道（{@link com.hykj.assistant.adapters.anythingllm.AnythingLlmRetriever}）仍然保留：
 * 出问题时把 {@code assistant.retrieval.mode} 切成 {@code kernel} 就能对照——两个实现
 * 靠 {@code @ConditionalOnProperty} 互斥，同一时刻只会装配一个，不会有两个
 * {@link KnowledgeRetriever} 打架。</p>
 *
 * <p>P0 重排（可选）：RRF 只看"两路各排第几"，不看"问题与片段到底像不像"。开启后把融合候选交给
 * {@link Reranker} 打相关性分，按分重排。**默认关闭**，且任何失败都 fail-open 退回 RRF 原序——
 * 重排是锦上添花，不能因为它出问题就检索不出结果。</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "assistant.retrieval.mode", havingValue = "local", matchIfMissing = true)
public class LocalChunkRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(LocalChunkRetriever.class);

    /** 两路各自多要一些再融合：融合会重排名次，只按 topN 取会漏掉"两路都排第二"的片段。 */
    private static final int OVERFETCH_FACTOR = 3;
    private static final int MIN_OVERFETCH = 12;
    /** 关键词通道自己的下限：低于它的连候选都不算（实测跑题问题在 0.2 以下）。 */
    public static final double KEYWORD_MIN_SCORE = 0.12;
    /**
     * 送进重排的候选上限：够覆盖 topN 的窗口（重排要能捞起 RRF 排在第 topN 之外的命中），
     * 又不至于超过重排服务单次能收的条数（{@code RerankClient.MAX_DOCUMENTS}）。
     */
    static final int RERANK_CANDIDATE_LIMIT = 20;

    private final DocumentChunkRepository chunks;
    private final TextEmbedder embedder;
    private final Reranker reranker;
    private final QueryRewriter queryRewriter;

    public LocalChunkRetriever(DocumentChunkRepository chunks, TextEmbedder embedder, Reranker reranker,
                               QueryRewriter queryRewriter) {
        this.chunks = chunks;
        this.embedder = embedder;
        this.reranker = reranker;
        this.queryRewriter = queryRewriter;
    }

    @Override
    public Outcome retrieve(Query query) throws IOException, InterruptedException {
        if (query.domainCode() == null || query.filter() == null) {
            throw new IllegalArgumentException("自建检索需要知识域与当前身份的可见范围（调用方没给）");
        }
        int overfetch = Math.max(query.topN() * OVERFETCH_FACTOR, MIN_OVERFETCH);
        // 改写关闭时 queries 只有原问题；开启时是"原问题 + 变体"，每条各跑两路后多路融合。
        List<String> queries = queries(query.question());
        List<float[]> vectors = embedder.embed(queries);
        List<List<ChunkRow>> vectorLists = new ArrayList<>(queries.size());
        List<List<ChunkRow>> keywordLists = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            vectorLists.add(chunks.searchVector(query.filter(), query.domainCode(), vectors.get(i), overfetch,
                    query.minScore()));
            keywordLists.add(chunks.searchKeyword(query.filter(), query.domainCode(), queries.get(i), overfetch,
                    KEYWORD_MIN_SCORE));
        }
        // 权限提示的来源：只用**原问题**的向量反向数一次"被挡下的命中"。只数一路——
        // 变体与关键词通道的命中几乎总被原问题向量覆盖，多路相加会把同一个片段数很多遍。
        int blocked = chunks.countBlockedCandidates(query.filter(), query.domainCode(), vectors.get(0),
                query.minScore());

        boolean rerank = reranker.enabled();
        // 重排要能捞起 RRF 排在第 topN 之外的命中，所以融合时先多留一些；不重排时维持原样取 topN。
        int fuseLimit = rerank ? RERANK_CANDIDATE_LIMIT : query.topN();
        List<FusedChunk> fused = queries.size() == 1
                ? RrfFusion.fuse(vectorLists.get(0), keywordLists.get(0), RrfFusion.DEFAULT_K, fuseLimit)
                : MultiQueryFusion.fuse(vectorLists, keywordLists, RrfFusion.DEFAULT_K, fuseLimit);
        log.debug("自建召回 domain={} 查询={}条 融合后={} 被权限挡下={} 重排={}", query.domainCode(),
                queries.size(), fused.size(), blocked, rerank);

        if (rerank && !fused.isEmpty()) {
            return new Outcome(rerank(query.question(), fused, query.topN()), blocked);
        }
        return new Outcome(fused.stream().map(item -> toPassage(item, null)).toList(), blocked);
    }

    /**
     * 待检索的查询列表：改写关闭时只有原问题（与引入改写前逐位一致）；开启时原问题在最前、后接变体。
     * 任何失败都 fail-open 退回"只有原问题"，且不吞掉中断标志。
     */
    private List<String> queries(String question) {
        if (!queryRewriter.enabled()) {
            return List.of(question);
        }
        try {
            List<String> variants = queryRewriter.rewrite(question);
            if (variants.isEmpty()) {
                return List.of(question);
            }
            List<String> all = new ArrayList<>(variants.size() + 1);
            all.add(question);
            all.addAll(variants);
            return all;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("查询改写被中断，退回原问题：{}", ex.toString());
            return List.of(question);
        } catch (IOException | RuntimeException ex) {
            log.warn("查询改写失败，退回原问题（fail-open）：{}", ex.toString());
            return List.of(question);
        }
    }

    /**
     * 重排。任何失败（网络/超时/解析错/条数不符/中断）都退回 RRF 原序，且不吞掉中断标志——
     * 一次重排失败不该改变"出不出结果"，但也不该让上层以为线程没被打断。
     */
    private List<RetrievedPassage> rerank(String question, List<FusedChunk> fused, int topN) {
        List<RetrievedPassage> fallback = fused.stream().map(item -> toPassage(item, null)).toList();
        try {
            List<Double> scores = reranker.score(question,
                    fused.stream().map(item -> item.chunk().content()).toList());
            List<RerankStage.Scored> ranked = RerankStage.apply(fused, scores);
            List<RetrievedPassage> passages = new ArrayList<>(Math.min(topN, ranked.size()));
            for (RerankStage.Scored item : ranked) {
                if (passages.size() >= topN) {
                    break;
                }
                passages.add(toPassage(item.chunk(), item.rerankScore()));
            }
            return passages;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("重排被中断，退回 RRF 原序：{}", ex.toString());
            return fallback;
        } catch (IOException | RuntimeException ex) {
            log.warn("重排失败，退回 RRF 原序（fail-open）：{}", ex.toString());
            return fallback;
        }
    }

    @Override
    public String displayName() {
        return "自建混合召回";
    }

    private static RetrievedPassage toPassage(FusedChunk item, Double rerankScore) {
        ChunkRow chunk = item.chunk();
        return new RetrievedPassage(
                String.valueOf(chunk.chunkId()),
                chunk.chunkIndex(),
                chunk.kernelDocName(),
                chunk.content(),
                item.vectorScore(),
                1 - item.vectorScore(),
                null,
                chunk.content().length(),
                0,
                chunk.pageFrom(),
                chunk.pageTo(),
                chunk.sectionPath(),
                chunk.kind(),
                item.keywordScore(),
                item.vectorRank(),
                item.keywordRank(),
                item.fusedScore(),
                rerankScore);
    }
}
