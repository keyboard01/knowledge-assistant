package com.hykj.assistant.adapters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 适配层共用的 HTTP 传输工具：统一超时、统一 HTTP/1.1、统一 JSON 解析与错误信息。
 * 它是"基础设施"，只有 adapters 下的实现类应该使用它；业务包不得直接调用。
 */
public final class HttpSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            // AnythingLLM（Node）不接受 Java 客户端默认的 h2c 升级请求，会返回 400
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(25);
    /** 上传一个文件会连带触发内核切分与向量化，慢是正常的。 */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(5);
    /** 流式生成：超时覆盖"整段响应读完"，所以给得比普通请求宽。 */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(3);

    private HttpSupport() {
    }

    public static JsonNode get(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET();
        return send(authorize(builder, bearer).build());
    }

    public static JsonNode postJson(String url, String bearer, Object body) throws IOException, InterruptedException {
        return postJson(url, bearer, body, READ_TIMEOUT);
    }

    /**
     * 带自定义超时的 POST。给"锦上添花、等不起"的调用用（如 rerank）——它们的失败应该快速退场，
     * 不能因为复用了普通请求的 25s 读超时，把一个挂掉的服务拖成调用方的整体超时。
     */
    public static JsonNode postJson(String url, String bearer, Object body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        return send(authorize(builder, bearer).build());
    }

    /**
     * 流式 POST（SSE）：每读到一条 {@code data: {...}} 就交给 {@code onData}，读不下整个响应。
     *
     * <p>只认标准 SSE 的 {@code data:} 行——{@code event:} / 注释行（{@code :keep-alive}）与
     * {@code [DONE]} 都会被跳过。响应里出现非 SSE 的裸 JSON（有的服务在 200 里塞一段错误对象，
     * 而不是标准的错误状态码）时直接抛错，免得把一段错误当成正文喂给调用方。</p>
     */
    public static void postJsonStream(String url, String bearer, Object body, Consumer<JsonNode> onData)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(STREAM_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        HttpResponse<Stream<String>> response = HTTP.send(authorize(builder, bearer).build(),
                HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() >= 400) {
            String payload;
            try (Stream<String> lines = response.body()) {
                payload = String.join(" ", (Iterable<String>) lines::iterator);
            }
            throw new IOException("HTTP " + response.statusCode() + " 来自 " + url + "：" + truncate(payload));
        }
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next().strip();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    if (line.startsWith("{") || line.startsWith("[")) {
                        throw new IOException("流式响应里出现非 SSE 的 JSON 内容：" + truncate(line));
                    }
                    continue; // event: / id: / retry: 等字段与正文无关
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(payload);
                } catch (Exception ex) {
                    throw new IOException("流式响应片段不是合法 JSON：" + truncate(payload));
                }
                onData.accept(node);
            }
        }
    }

    public static JsonNode deleteJson(String url, String bearer, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        return send(authorize(builder, bearer).build());
    }

    /**
     * multipart/form-data 上传。JDK 的 HttpClient 没有现成的 multipart 构造器，
     * 手写边界即可——这里只需要"若干文本字段 + 一个文件"这点能力。
     * 文件名必须是 ASCII：内核用它当文档名，中文名在 multer 里会变成乱码。
     */
    public static JsonNode postMultipart(String url, String bearer, Map<String, String> fields,
                                  String fileField, String fileName, String contentType, byte[] fileContent)
            throws IOException, InterruptedException {
        String boundary = "----knowledgeAssistant" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null || field.getValue().isEmpty()) {
                continue;
            }
            writeUtf8(buffer, "--" + boundary + "\r\n");
            writeUtf8(buffer, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
            writeUtf8(buffer, field.getValue());
            writeUtf8(buffer, "\r\n");
        }
        if (fileContent != null) {
            writeUtf8(buffer, "--" + boundary + "\r\n");
            writeUtf8(buffer, "Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + fileName + "\"\r\n");
            writeUtf8(buffer, "Content-Type: " + contentType + "\r\n\r\n");
            buffer.write(fileContent);
            writeUtf8(buffer, "\r\n");
        }
        writeUtf8(buffer, "--" + boundary + "--\r\n");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(UPLOAD_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()));
        return send(authorize(builder, bearer).build());
    }

    private static void writeUtf8(ByteArrayOutputStream buffer, String text) throws IOException {
        buffer.write(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String normalizeBase(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public static String brief(JsonNode node) {
        if (node == null) {
            return null;
        }
        String flat = node.toString().replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    private static HttpRequest.Builder authorize(HttpRequest.Builder builder, String bearer) {
        if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer.trim());
        }
        return builder;
    }

    private static JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " 来自 " + request.uri()
                    + "：" + truncate(body));
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        String probe = body.stripLeading().toLowerCase();
        if (probe.startsWith("<!doctype") || probe.startsWith("<html")) {
            throw new IOException("返回了 HTML 页面而非 JSON，接口路径可能与当前版本不匹配：" + request.uri());
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            throw new IOException("响应不是合法 JSON：" + truncate(body));
        }
    }

    private static String truncate(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
