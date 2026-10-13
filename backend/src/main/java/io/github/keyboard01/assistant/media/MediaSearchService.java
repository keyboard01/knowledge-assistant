package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.embedding.TextEmbedder;
import io.github.keyboard01.assistant.domain.DomainRepository;
import io.github.keyboard01.assistant.domain.DomainService;
import io.github.keyboard01.assistant.domain.KnowledgeDomain;
import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AccessSubject;

/**
 * 找图检索：向量 + 关键词两路，RRF 融合。
 *
 * <p>与文本检索同一套口径：先过域级门（{@link DomainService#requireAccessible}），
 * 再把文档级 ACL 与"现行"状态**下推进 SQL**——因此无权/未生效的图根本不出现在候选里。</p>
 */
@Service
public class MediaSearchService {

    private static final int OVERFETCH = 3;
    private static final int DEFAULT_TOP_N = 8;
    private static final int MAX_TOP_N = 30;
    /** 与文本检索一致：向量的召回下限较宽，关键词另有一条更松的下限。 */
    private static final double DEFAULT_MIN_SCORE = 0.20;
    private static final double KEYWORD_MIN_SCORE = 0.12;
    private static final int RRF_K = 60;

    private final ImageAssetRepository assets;
    private final TextEmbedder embedder;
    private final ImageEmbedder imageEmbedder;
    private final DomainService domainService;
    private final DomainRepository domains;

    public MediaSearchService(ImageAssetRepository assets, TextEmbedder embedder, ImageEmbedder imageEmbedder,
                              DomainService domainService, DomainRepository domains) {
        this.assets = assets;
        this.embedder = embedder;
        this.imageEmbedder = imageEmbedder;
        this.domainService = domainService;
        this.domains = domains;
    }

    public MediaModels.SearchResult search(AccessSubject subject, String domainCode, String question, Integer topN,
                                           Double minScore, Map<String, String> imageCfg)
            throws IOException, InterruptedException {
        String code = require(domainCode, "请选择知识域");
        String query = require(question, "请输入要找的图片描述");
        int want = clamp(topN == null ? DEFAULT_TOP_N : topN, 1, MAX_TOP_N);
        double floor = clamp(minScore == null ? DEFAULT_MIN_SCORE : minScore, 0d, 1d);

        // 域不存在与无权访问同形（不暴露存在性）
        domainService.requireAccessible(subject, code);
        KnowledgeDomain domain = domains.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        if (!domain.enabled()) {
            throw new IllegalArgumentException("知识域已停用：" + domain.name());
        }

        AccessFilter filter = AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS);
        int fetch = want * OVERFETCH;
        float[] queryVector = embedder.embed(List.of(query)).get(0);
        List<MediaModels.Hit> vectorHits = assets.searchVector(filter, code, queryVector, fetch, floor);
        List<MediaModels.Hit> keywordHits = assets.searchKeyword(filter, code, query, fetch, KEYWORD_MIN_SCORE);
        // 第三路（可选）：视觉向量——查询文本由同一个多模态模型编码，直接与图片向量比
        List<MediaModels.Hit> visionHits = List.of();
        if (imageEmbedder.enabled(imageCfg)) {
            float[] visionQuery = imageEmbedder.embedText(query, imageCfg);
            visionHits = assets.searchVision(filter, code, visionQuery, fetch, floor);
        }

        List<MediaModels.Hit> hits = fuse(vectorHits, keywordHits, visionHits, want);
        return new MediaModels.SearchResult(hits, hits.isEmpty() ? "没有找到匹配的图片。" : null);
    }

    /** 只按名次融合（RRF）：三路的分数量纲各不相同，相加等于把米和公斤相加。 */
    private static List<MediaModels.Hit> fuse(List<MediaModels.Hit> vectorHits, List<MediaModels.Hit> keywordHits,
                                              List<MediaModels.Hit> visionHits, int want) {
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, MediaModels.Hit> byId = new LinkedHashMap<>();
        accumulate(vectorHits, fused, byId);
        accumulate(keywordHits, fused, byId);
        accumulate(visionHits, fused, byId);
        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(want)
                .map(entry -> {
                    MediaModels.Hit hit = byId.get(entry.getKey());
                    return new MediaModels.Hit(hit.assetId(), hit.documentId(), hit.docNo(), hit.title(),
                            hit.version(), hit.page(), hit.ext(), hit.caption(), entry.getValue());
                })
                .toList();
    }

    private static void accumulate(List<MediaModels.Hit> hits, Map<Long, Double> fused,
                                   Map<Long, MediaModels.Hit> byId) {
        for (int i = 0; i < hits.size(); i++) {
            MediaModels.Hit hit = hits.get(i);
            byId.putIfAbsent(hit.assetId(), hit);
            fused.merge(hit.assetId(), 1.0d / (RRF_K + i + 1), Double::sum);
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
