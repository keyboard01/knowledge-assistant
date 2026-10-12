package io.github.keyboard01.assistant.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.keyboard01.assistant.parse.ChunkOptions;

/**
 * 切分预算的装配层：把 {@code assistant.chunking.*} 配置读成一个 {@link ChunkOptions}。
 *
 * <p>解析与切分本身是纯函数（{@link io.github.keyboard01.assistant.parse.ChunkSplitter} 不依赖 Spring），
 * 配置只在装配层注入，测试可以直接喂 {@link ChunkOptions}。默认值与 {@link ChunkOptions#DEFAULT}
 * 一致（贴近 200 字旧行为，只是改按 token 口径算）。</p>
 */
@Component
public class ChunkingSettings {

    private final ChunkOptions options;

    public ChunkingSettings(
            @Value("${assistant.chunking.min-tokens:60}") int minTokens,
            @Value("${assistant.chunking.target-tokens:150}") int targetTokens,
            @Value("${assistant.chunking.max-tokens:200}") int maxTokens,
            @Value("${assistant.chunking.overlap-tokens:30}") int overlapTokens) {
        this.options = ChunkOptions.of(minTokens, targetTokens, maxTokens, overlapTokens);
    }

    public ChunkOptions options() {
        return options;
    }
}
