package io.github.keyboard01.assistant.adapters.anythingllm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.keyboard01.assistant.adapters.anythingllm.AnythingLlmClient.RawHit;
import io.github.keyboard01.assistant.retrieval.KnowledgeRetriever;
import io.github.keyboard01.assistant.retrieval.RetrievedPassage;
import io.github.keyboard01.assistant.settings.SettingService;

/**
 * {@link KnowledgeRetriever} 的 AnythingLLM 实现，也就是防腐层：
 * 内核的工作区 slug、请求体格式、响应字段、正文里塞的元数据块，全部关在这个类里。
 * 业务侧拿到的是 {@link RetrievedPassage}——干净正文 + 业务需要的字段，换内核不影响它们。
 *
 * <p>P2 起默认不装配这个实现：{@code assistant.retrieval.mode=kernel} 才会启用。
 * 原因见 {@link io.github.keyboard01.assistant.retrieval.RrfFusion}——内核返回的相似度被压扁过，
 * 分不开"该答/不该答"，只能当对照通道。</p>
 */
@Component
@ConditionalOnProperty(name = "assistant.retrieval.mode", havingValue = "kernel")
public class AnythingLlmRetriever implements KnowledgeRetriever {

    private static final String META_OPEN = "<document_metadata>";
    private static final String META_CLOSE = "</document_metadata>";

    private final AnythingLlmClient kernel;
    private final SettingService settingService;

    public AnythingLlmRetriever(AnythingLlmClient kernel, SettingService settingService) {
        this.kernel = kernel;
        this.settingService = settingService;
    }

    @Override
    public Outcome retrieve(Query query) throws IOException, InterruptedException {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_ANYTHINGLLM);
        List<RawHit> hits = kernel.vectorSearch(cfg, query.workspaceSlug(), query.question(),
                query.topN(), query.minScore());
        // 内核不做权限过滤：被挡下的命中会照常出现在候选里，由 EvidenceAssembler 数出来，
        // 所以这里报 0（报别的会重复计数）。
        return Outcome.of(hits.stream().map(AnythingLlmRetriever::toPassage).toList());
    }

    @Override
    public String displayName() {
        return "内核 vector-search";
    }

    private static RetrievedPassage toPassage(RawHit hit) {
        Map<String, String> metadata = hit.metadata();
        Map<String, String> header = new LinkedHashMap<>();
        String text = stripMetadataBlock(hit.text(), header);
        return new RetrievedPassage(
                hit.id(),
                firstNonBlank(header.get("sourceDocument"), metadata.get("title")),
                text,
                hit.score(),
                hit.distance(),
                firstNonBlank(header.get("published"), metadata.get("published")),
                number(metadata.get("wordCount")),
                number(metadata.get("tokenCount")));
    }

    /**
     * 剥掉内核注入的元数据块并解析成键值对。它不属于原文，留着会污染引用和回答。
     * 格式一旦变了就原样返回——宁可多带一行噪声，也不猜错正文。
     */
    static String stripMetadataBlock(String text, Map<String, String> header) {
        String trimmed = text == null ? "" : text.stripLeading();
        if (!trimmed.startsWith(META_OPEN)) {
            return trimmed;
        }
        int close = trimmed.indexOf(META_CLOSE);
        if (close < 0) {
            return trimmed;
        }
        for (String line : trimmed.substring(META_OPEN.length(), close).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return trimmed.substring(close + META_CLOSE.length()).stripLeading();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static int number(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
