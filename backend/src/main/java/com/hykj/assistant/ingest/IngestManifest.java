package com.hykj.assistant.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * manifest.json：目录扫描只负责"发现文件"，元数据一律以登记表为准。
 * 没登记的文件不进库——宁可漏，不可猜，检索质量依赖元数据可信。
 */
public final class IngestManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String file, String domainCode, String docNo, String title, String version,
                        String docStatus, String secrecy, String ownerDept,
                        String effectiveFrom, String effectiveTo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(List<Entry> documents) {
    }

    public record Loaded(Payload payload, Map<String, Entry> byRelativePath) {
    }

    private IngestManifest() {
    }

    public static Loaded load(Path root) throws IOException {
        Path manifestFile = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("文档根目录缺少 manifest.json：" + manifestFile);
        }
        Payload payload = MAPPER.readValue(Files.readString(manifestFile, StandardCharsets.UTF_8), Payload.class);
        Map<String, Entry> index = new LinkedHashMap<>();
        if (payload.documents() != null) {
            for (Entry entry : payload.documents()) {
                index.put(normalize(entry.file()), entry);
            }
        }
        return new Loaded(payload, index);
    }

    /** Windows 的路径分隔符不该泄漏进登记表比对。 */
    public static String normalize(String relativePath) {
        return relativePath == null ? "" : relativePath.replace('\\', '/');
    }
}
