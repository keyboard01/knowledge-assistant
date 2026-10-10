package com.hykj.assistant.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * manifest.json 的写入口（P1 管理能力：浏览器上传）。
 *
 * <p>上传是"目录 + manifest 登记"这套链路的唯一写入口：文件落盘后必须同步登记，
 * 否则扫描时会被当成"未登记文件"跳过。这里按 {@code (docNo, version)} 去重——**一个版本只留一份文件**，
 * 替换时把旧文件返回给调用方删除；保留 {@code _comment} 与未知顶层字段；写入用临时文件 + 原子重命名，
 * 避免写一半被打断留下半个 JSON。</p>
 *
 * <p>取舍：单机串行假设，没有文件锁——并发上传可能互相覆盖 manifest。当前是单租户部署，先按已知限制登记。</p>
 */
public final class ManifestWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ManifestWriter() {
    }

    /**
     * 登记一个文件。
     *
     * @return 被替换掉的旧文件相对路径（没有则 null），调用方据此清理磁盘旧文件
     */
    public static String upsert(Path root, IngestManifest.Entry entry) throws IOException {
        Path manifestFile = root.resolve("manifest.json");
        ObjectNode document = readOrCreate(manifestFile);
        ArrayNode documents = document.withArray("documents");

        String relative = IngestManifest.normalize(entry.file());
        String replaced = null;
        List<JsonNode> remaining = new ArrayList<>();
        for (JsonNode node : documents) {
            String file = IngestManifest.normalize(node.path("file").asText(""));
            boolean sameFile = file.equals(relative);
            boolean sameVersion = node.path("docNo").asText("").equals(entry.docNo())
                    && node.path("version").asText("").equals(entry.version());
            if (sameFile || sameVersion) {
                if (replaced == null && !sameFile) {
                    replaced = file; // 同版本换了文件名：旧文件待删
                }
                continue; // 丢弃旧条目（同文件覆盖 或 同版本换文件）
            }
            remaining.add(node);
        }
        remaining.add(MAPPER.valueToTree(entry));

        ArrayNode updated = MAPPER.createArrayNode();
        remaining.forEach(updated::add);
        document.set("documents", updated);
        writeAtomically(manifestFile, document);
        return replaced;
    }

    private static ObjectNode readOrCreate(Path manifestFile) throws IOException {
        if (Files.isRegularFile(manifestFile)) {
            JsonNode node = MAPPER.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IOException("manifest.json 顶层不是对象：" + manifestFile);
        }
        return MAPPER.createObjectNode();
    }

    private static void writeAtomically(Path manifestFile, JsonNode document) throws IOException {
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        Path temp = manifestFile.resolveSibling("manifest.json.tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, manifestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            // 某些文件系统不支持原子移动：退回普通替换，至少不留下半个 JSON
            Files.move(temp, manifestFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
