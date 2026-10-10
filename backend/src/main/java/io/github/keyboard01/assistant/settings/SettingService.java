package io.github.keyboard01.assistant.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.adapters.anythingllm.KernelGateway;
import io.github.keyboard01.assistant.adapters.OpenAiCompatibleClient;
import io.github.keyboard01.assistant.adapters.EmbeddingProbeClient;
import io.github.keyboard01.assistant.adapters.rerank.RerankClient;
import io.github.keyboard01.assistant.adapters.layout.LayoutClient;
import io.github.keyboard01.assistant.settings.SettingModels.FieldDef;
import io.github.keyboard01.assistant.settings.SettingModels.FieldView;
import io.github.keyboard01.assistant.settings.SettingModels.GroupView;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

@Service
public class SettingService {

    public static final String GROUP_ANYTHINGLLM = "anythingllm";
    public static final String GROUP_EMBEDDING = "embedding";
    public static final String GROUP_LLM = "llm";
    public static final String GROUP_INGEST = "ingest";
    public static final String GROUP_RERANK = "rerank";
    public static final String GROUP_LAYOUT = "layout";

    private record GroupDef(String code, String title, String description, List<FieldDef> fields) {
    }

    private static final Map<String, GroupDef> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put(GROUP_ANYTHINGLLM, new GroupDef(GROUP_ANYTHINGLLM, "AnythingLLM 连接",
                "文档仓与向量检索内核。密钥只保存在服务端，接口只回显掩码。",
                List.of(
                        new FieldDef("baseUrl", "服务地址", false,
                                "本项目专用内核容器默认 http://127.0.0.1:3002，见 deploy\\start-kernel.ps1",
                                "http://127.0.0.1:3002"),
                        new FieldDef("apiKey", "Developer API Key", true,
                                "AnythingLLM → 设置 → 工具 → 开发者 API（生成后仅显示一次）", ""))));

        GROUPS.put(GROUP_EMBEDDING, new GroupDef(GROUP_EMBEDDING, "向量化 Embedding",
                "决定“能不能凭意思检索到”。一旦入库就必须保持一致，更换需全量重建索引。",
                List.of(
                        new FieldDef("baseUrl", "接口地址", false,
                                "兼容 OpenAI 协议的地址，不要带末尾斜杠", ""),
                        new FieldDef("apiKey", "API Key", true,
                                "服务商控制台 → API-KEY 管理", ""),
                        new FieldDef("model", "模型名", false,
                                "按服务商文档填写", ""),
                        new FieldDef("dimension", "向量维度", false,
                                "与模型一致（本系统 DDL 固定为 1024）", "1024"))));

        GROUPS.put(GROUP_LLM, new GroupDef(GROUP_LLM, "生成模型出口（LLM）",
                "用于答案生成、查询改写与引用校验。可与向量化使用同一个 API Key。",
                List.of(
                        new FieldDef("baseUrl", "接口地址", false,
                                "兼容 OpenAI 协议的地址", ""),
                        new FieldDef("apiKey", "API Key", true,
                                "留空表示复用向量化分组的 Key", ""),
                        new FieldDef("model", "模型名", false,
                                "按服务商文档填写", ""),
                        new FieldDef("temperature", "温度", false,
                                "知识问答建议 0.1 ~ 0.3", "0.2"),
                        // 单价属于观测口径，放在生成分组是因为它只跟这个模型有关。
                        // 填了才算费用，留空就只记 token——宁可显示"未配置单价"，也不要用 0 冒充"免费"。
                        new FieldDef("promptPricePer1k", "输入单价（元/千 token）", false,
                                "留空表示不计算费用。单价会随每条账本快照，事后改价不影响已记录的历史成本", ""),
                        new FieldDef("completionPricePer1k", "输出单价（元/千 token）", false,
                                "留空表示不计算费用。单价以模型官网现价为准", ""))));

        GROUPS.put(GROUP_INGEST, new GroupDef(GROUP_INGEST, "文档同步",
                "扫描哪个目录。每个知识域一个子目录，且根目录下需有 manifest.json 登记文档元数据。",
                List.of(
                        new FieldDef("sourceRoot", "文档根目录", false,
                                "绝对路径，或相对后端工作目录（项目根）的路径", "knowledge"),
                        new FieldDef("contextual", "片段上下文增强", false,
                                "用生成模型给每个片段补一句上下文后再向量化（P1）。默认关闭；"
                                        + "开启后需重建片段（POST /ingest/reindex）才生效", "false"))));

        GROUPS.put(GROUP_RERANK, new GroupDef(GROUP_RERANK, "重排 Rerank（可选）",
                "在 RRF 之后用 cross-encoder 精排候选，压掉“语义相近但答非所问”的片段。"
                        + "默认关闭；只改排序，不改作答阈值口径。",
                List.of(
                        new FieldDef("enabled", "启用重排", false,
                                "填 true 才生效。开启前请确认已按新分数口径复核作答阈值（见差距清单 §7.1）", "false"),
                        new FieldDef("baseUrl", "接口地址", false,
                                "兼容 /rerank 的服务地址（如 TEI / vLLM），不要带末尾斜杠",
                                ""),
                        new FieldDef("apiKey", "API Key", true,
                                "本地部署可留空；云端按服务商要求填写", ""),
                        new FieldDef("model", "模型名", false,
                                "按服务商文档填写", ""))));

        GROUPS.put(GROUP_LAYOUT, new GroupDef(GROUP_LAYOUT, "版面分析（可选）",
                "用外部版面分析服务（Docling / MinerU / PP-StructureV3）解析扫描件、双栏与跨页表格。"
                        + "默认关闭；服务不可用时自动回落到内置 PDFBox/POI 解析。",
                List.of(
                        new FieldDef("enabled", "启用版面分析", false,
                                "填 true 才生效。关闭时解析完全走内置 PDFBox/POI", "false"),
                        new FieldDef("baseUrl", "服务地址", false,
                                "需实现 POST {baseUrl}/parse（multipart 字段 file）→ {blocks:[{kind,text,page,level}]}",
                                ""),
                        new FieldDef("apiKey", "API Key", true,
                                "本地部署可留空；云端按服务商要求填写", ""))));
    }

    private final SettingRepository repository;
    private final KernelGateway kernelGateway;
    private final EmbeddingProbeClient embeddingProbeClient;
    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final RerankClient rerankClient;
    private final LayoutClient layoutClient;

    public SettingService(SettingRepository repository,
                          KernelGateway kernelGateway,
                          EmbeddingProbeClient embeddingProbeClient,
                          OpenAiCompatibleClient openAiCompatibleClient,
                          RerankClient rerankClient,
                          LayoutClient layoutClient) {
        this.repository = repository;
        this.kernelGateway = kernelGateway;
        this.embeddingProbeClient = embeddingProbeClient;
        this.openAiCompatibleClient = openAiCompatibleClient;
        this.rerankClient = rerankClient;
        this.layoutClient = layoutClient;
    }

    public List<GroupView> list() {
        Map<String, String> stored = repository.loadAll();
        List<GroupView> groups = new ArrayList<>();
        for (GroupDef def : GROUPS.values()) {
            groups.add(toView(def, stored));
        }
        return groups;
    }

    public GroupView save(String code, Map<String, String> values, String operator) {
        GroupDef def = require(code);
        for (FieldDef field : def.fields()) {
            String raw = values.get(field.key());
            if (raw == null) {
                continue;
            }
            raw = raw.trim();
            if (field.secret() && raw.isEmpty()) {
                continue; // 空值视为“不修改”，避免掩码回填把密钥覆盖掉
            }
            if (!field.secret() && raw.isEmpty()) {
                repository.delete(code, field.key());
                continue;
            }
            repository.upsert(code, field.key(), raw, field.secret(), operator);
        }
        return toView(def, repository.loadAll());
    }

    public TestResult test(String code) {
        Map<String, String> cfg = effective(require(code));
        return switch (code) {
            case GROUP_ANYTHINGLLM -> kernelGateway.probe(cfg);
            case GROUP_EMBEDDING -> embeddingProbeClient.probeEmbedding(cfg);
            case GROUP_LLM -> openAiCompatibleClient.probeChat(cfg);
            case GROUP_RERANK -> rerankClient.probe(cfg);
            case GROUP_LAYOUT -> layoutClient.probe(cfg);
            case GROUP_INGEST -> probeIngestRoot(cfg);
            default -> new TestResult(false, "未知配置分组：" + code, null);
        };
    }

    /** 同步目录体检：目录在不在、登记表读不读得到、有多少文件待扫描。 */
    private TestResult probeIngestRoot(Map<String, String> cfg) {
        String value = cfg.get("sourceRoot");
        Path path = Path.of(value == null || value.isBlank() ? "knowledge" : value.trim());
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        path = path.normalize().toAbsolutePath();
        if (!Files.isDirectory(path)) {
            return new TestResult(false, "目录不存在：" + path, null);
        }
        if (!Files.isRegularFile(path.resolve("manifest.json"))) {
            return new TestResult(false, "目录存在但缺少 manifest.json（文档必须有登记元数据）：" + path, null);
        }
        try (Stream<Path> stream = Files.walk(path)) {
            long files = stream.filter(Files::isRegularFile)
                    .filter(item -> !item.getFileName().toString().startsWith("."))
                    .count();
            return new TestResult(true, "目录可用：" + path + "，待扫描文件 " + files + " 个", null);
        } catch (IOException ex) {
            return new TestResult(false, "读取目录失败：" + ex.getMessage(), null);
        }
    }

    public GroupView clear(String code, String fieldKey, String operator) {
        GroupDef def = require(code);
        boolean known = def.fields().stream().anyMatch(field -> field.key().equals(fieldKey));
        if (!known) {
            throw new IllegalArgumentException("未知字段：" + code + "." + fieldKey);
        }
        repository.delete(code, fieldKey);
        return toView(def, repository.loadAll());
    }

    public Map<String, String> effective(String code) {
        return effective(require(code));
    }

    /**
     * 生效配置 = 存库的值（没有则取默认值）。
     *
     * <p>生成模型分组的 Key 允许留空并复用向量化分组的 Key：界面提示就是这么写的，
     * 实现必须一致——"提示说可以留空、实际留空就报未配置"是最难排查的一类不一致。
     * 回落只发生在这一个字段上，其他字段留空就是留空。</p>
     */
    private Map<String, String> effective(GroupDef def) {
        Map<String, String> stored = repository.loadAll();
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDef field : def.fields()) {
            String storedValue = stored.get(def.code() + "." + field.key());
            out.put(field.key(), storedValue == null || storedValue.isBlank()
                    ? field.defaultValue() : storedValue);
        }
        if (GROUP_LLM.equals(def.code()) && isBlank(out.get("apiKey"))) {
            out.put("apiKey", effective(require(GROUP_EMBEDDING)).get("apiKey"));
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private GroupDef require(String code) {
        GroupDef def = GROUPS.get(code);
        if (def == null) {
            throw new IllegalArgumentException("未知配置分组：" + code);
        }
        return def;
    }

    private GroupView toView(GroupDef def, Map<String, String> stored) {
        List<FieldView> fields = new ArrayList<>();
        for (FieldDef field : def.fields()) {
            String value = stored.get(def.code() + "." + field.key());
            boolean hasValue = value != null && !value.isBlank();
            if (field.secret()) {
                // 复用来的 Key 也要如实显示"有值"：否则用户会以为没配，
                // 一次次去填一个本来就不需要的 Key。掩码位置放的是来源说明，不是密钥本身。
                String fallbackKey = stored.get(GROUP_EMBEDDING + ".apiKey");
                if (!hasValue && GROUP_LLM.equals(def.code()) && "apiKey".equals(field.key())
                        && !isBlank(fallbackKey)) {
                    fields.add(new FieldView(field.key(), field.label(), true, true, "",
                            "复用向量化分组的 Key", field.hint()));
                } else {
                    fields.add(new FieldView(field.key(), field.label(), true, hasValue,
                            "", hasValue ? mask(value) : "", field.hint()));
                }
            } else {
                fields.add(new FieldView(field.key(), field.label(), false,
                        hasValue, hasValue ? value : field.defaultValue(), "", field.hint()));
            }
        }
        return new GroupView(def.code(), def.title(), def.description(), fields);
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
