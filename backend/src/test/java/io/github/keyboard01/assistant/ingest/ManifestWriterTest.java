package io.github.keyboard01.assistant.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上传是 manifest 的唯一写入口，写坏了会让整份登记表失去可信度（未登记=不入库）。
 * 这里钉住三件事：保留未知字段（_comment 等）、按 (docNo,version) 去重、不同版本各自保留。
 */
class ManifestWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("写入新条目：保留 _comment 与未知顶层字段")
    void upsertPreservesUnknownFields(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("manifest.json"),
                "{\"_comment\":\"保留我\",\"custom\":123,\"documents\":[]}", StandardCharsets.UTF_8);

        ManifestWriter.upsert(root, entry("policy/A_V1.pdf", "QM-1", "V1"));

        JsonNode json = read(root);
        assertEquals("保留我", json.path("_comment").asText());
        assertEquals(123, json.path("custom").asInt());
        assertEquals(1, json.path("documents").size());
        assertEquals("policy/A_V1.pdf", json.path("documents").get(0).path("file").asText());
    }

    @Test
    @DisplayName("同 (docNo,version) 换文件：替换旧条目并返回旧路径供清理")
    void replacesSameVersionAndReturnsOldFile(@TempDir Path root) throws Exception {
        ManifestWriter.upsert(root, entry("policy/A_V1.pdf", "QM-1", "V1"));

        String replaced = ManifestWriter.upsert(root, entry("policy/A_V1_fixed.pdf", "QM-1", "V1"));

        assertEquals("policy/A_V1.pdf", replaced);
        JsonNode json = read(root);
        assertEquals(1, json.path("documents").size(), "一个版本只留一份文件");
        assertEquals("policy/A_V1_fixed.pdf", json.path("documents").get(0).path("file").asText());
    }

    @Test
    @DisplayName("同路径覆盖：不返回旧文件（文件被就地替换，不该被删）")
    void sameFileOverwriteReturnsNoReplacement(@TempDir Path root) throws Exception {
        ManifestWriter.upsert(root, entry("policy/A_V1.pdf", "QM-1", "V1"));

        String replaced = ManifestWriter.upsert(root, entry("policy/A_V1.pdf", "QM-1", "V1"));

        assertNull(replaced);
        assertEquals(1, read(root).path("documents").size());
    }

    @Test
    @DisplayName("不同版本各自保留，互不覆盖")
    void keepsOtherVersions(@TempDir Path root) throws Exception {
        ManifestWriter.upsert(root, entry("policy/A_V1.pdf", "QM-1", "V1"));
        ManifestWriter.upsert(root, entry("policy/A_V2.pdf", "QM-1", "V2"));

        assertEquals(2, read(root).path("documents").size());
    }

    private static JsonNode read(Path root) throws Exception {
        return MAPPER.readTree(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8));
    }

    private static IngestManifest.Entry entry(String file, String docNo, String version) {
        return new IngestManifest.Entry(file, "policy", docNo, "标题", version, "现行", "内部公开", "财务部",
                null, null);
    }
}
