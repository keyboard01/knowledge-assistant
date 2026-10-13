package io.github.keyboard01.assistant.adapters.imageembedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 多模态向量响应的防御式解析（不连网、不启 Spring）。 */
class DashScopeMultimodalEmbedderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("百炼形状：output.embeddings[0].embedding")
    void parsesDashscopeShape() throws Exception {
        float[] vector = DashScopeMultimodalEmbedder.parseVector(
                MAPPER.readTree("{\"output\":{\"embeddings\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}}"));
        assertEquals(3, vector.length);
        assertEquals(0.2f, vector[1], 1e-6);
    }

    @Test
    @DisplayName("兼容单向量与 OpenAI 形状")
    void parsesFallbackShapes() throws Exception {
        assertEquals(2, DashScopeMultimodalEmbedder.parseVector(
                MAPPER.readTree("{\"output\":{\"embedding\":[1,2]}}")).length);
        assertEquals(2, DashScopeMultimodalEmbedder.parseVector(
                MAPPER.readTree("{\"data\":[{\"embedding\":[1,2]}]}")).length);
    }

    @Test
    @DisplayName("响应里没有向量 → IOException")
    void throwsWhenMissing() throws Exception {
        assertThrows(IOException.class,
                () -> DashScopeMultimodalEmbedder.parseVector(MAPPER.readTree("{\"output\":{}}")));
    }
}
