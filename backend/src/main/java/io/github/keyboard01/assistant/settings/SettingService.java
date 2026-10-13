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

import io.github.keyboard01.assistant.adapters.OpenAiCompatibleClient;
import io.github.keyboard01.assistant.adapters.EmbeddingProbeClient;
import io.github.keyboard01.assistant.adapters.dingtalk.DingTalkClient;
import io.github.keyboard01.assistant.adapters.rerank.RerankClient;
import io.github.keyboard01.assistant.adapters.layout.LayoutClient;
import io.github.keyboard01.assistant.adapters.vision.OpenAiCompatibleVisionClient;
import io.github.keyboard01.assistant.adapters.imageembedding.DashScopeMultimodalEmbedder;
import io.github.keyboard01.assistant.settings.SettingModels.FieldDef;
import io.github.keyboard01.assistant.settings.SettingModels.FieldView;
import io.github.keyboard01.assistant.settings.SettingModels.GroupView;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

@Service
public class SettingService {

    public static final String GROUP_EMBEDDING = "embedding";
    public static final String GROUP_LLM = "llm";
    public static final String GROUP_INGEST = "ingest";
    public static final String GROUP_RERANK = "rerank";
    public static final String GROUP_LAYOUT = "layout";
    public static final String GROUP_DINGTALK = "dingtalk";
    public static final String GROUP_VISION = "vision";
    public static final String GROUP_IMAGE_EMBEDDING = "image_embedding";

    private record GroupDef(String code, String title, String description, List<FieldDef> fields) {
    }

    private static final Map<String, GroupDef> GROUPS = new LinkedHashMap<>();

    static {
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
                        new FieldDef("assetRoot", "图片资产目录", false,
                                "「找图」抽取/上传的图片落盘位置（内容寻址）。不得位于文档根目录之内", "knowledge-assets"),
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

        GROUPS.put(GROUP_DINGTALK, new GroupDef(GROUP_DINGTALK, "钉钉（组织同步与登录）",
                "接入钉钉作为身份源：同步组织通讯录，并支持钉钉内免登。默认关闭；凭据留空即不启用。",
                List.of(
                        new FieldDef("enabled", "启用钉钉", false,
                                "填 true 且填好 appKey/appSecret 才生效；否则登录入口不显示、同步接口直接报未配置",
                                "false"),
                        new FieldDef("appKey", "AppKey", false,
                                "钉钉开放平台应用凭据。非密钥字段，便于清空重填", ""),
                        new FieldDef("appSecret", "AppSecret", true,
                                "钉钉开放平台应用密钥；只保存在服务端，接口只回显掩码", ""),
                        new FieldDef("corpId", "企业 CorpId", false,
                                "钉钉内免登（dd.config）需要", ""),
                        new FieldDef("agentId", "应用 AgentId", false,
                                "钉钉内免登（dd.config）需要", ""),
                        new FieldDef("rootDeptId", "同步起始部门 ID", false,
                                "从哪个部门开始递归同步，根部门固定为 1", "1"),
                        new FieldDef("defaultRole", "同步用户默认角色", false,
                                "组织同步进来的账号默认角色（永不为 admin，填 admin 会被降为 ops）", "ops"))));

        GROUPS.put(GROUP_VISION, new GroupDef(GROUP_VISION, "图片理解 Vision（可选）",
                "「找图」用：把图片交给视觉模型生成描述与图内文字，再据此建索引。"
                        + "默认关闭；关闭时不抽取、不描述图片（图片资产整条流水线跳过）。",
                List.of(
                        new FieldDef("enabled", "启用图片理解", false,
                                "填 true 且配好地址/Key/模型才生效", "false"),
                        new FieldDef("baseUrl", "接口地址", false,
                                "兼容 OpenAI 的 /chat/completions，且支持图片输入（如 Qwen-VL、GPT-4o）", ""),
                        new FieldDef("apiKey", "API Key", true,
                                "服务商控制台申请；只保存在服务端", ""),
                        new FieldDef("model", "模型名", false,
                                "必须是支持图片输入的模型，如 qwen-vl-max / gpt-4o", ""))));

        GROUPS.put(GROUP_IMAGE_EMBEDDING, new GroupDef(GROUP_IMAGE_EMBEDDING, "图片视觉向量（可选）",
                "「找图」的跨模态召回：用多模态模型把图片与查询文本编码进同一空间，"
                        + "补上「只靠文字描述」搜不到的纯视觉特征。默认关闭。",
                List.of(
                        new FieldDef("enabled", "启用视觉向量", false,
                                "填 true 且填好 API Key 与模型名才生效", "false"),
                        new FieldDef("baseUrl", "接口地址", false,
                                "阿里云百炼多模态 embedding 端点；留空用默认（北京地域）",
                                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"),
                        new FieldDef("apiKey", "API Key", true, "百炼控制台申请；只保存在服务端", ""),
                        new FieldDef("model", "模型名", false,
                                "多模态向量模型，默认 qwen2.5-vl-embedding（1024 维，与库内固定维度一致）",
                                "qwen2.5-vl-embedding"))));
    }

    private final SettingRepository repository;
    private final EmbeddingProbeClient embeddingProbeClient;
    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final RerankClient rerankClient;
    private final LayoutClient layoutClient;
    private final DingTalkClient dingTalkClient;
    private final OpenAiCompatibleVisionClient visionClient;
    private final DashScopeMultimodalEmbedder imageEmbedderClient;

    public SettingService(SettingRepository repository,
                          EmbeddingProbeClient embeddingProbeClient,
                          OpenAiCompatibleClient openAiCompatibleClient,
                          RerankClient rerankClient,
                          LayoutClient layoutClient,
                          DingTalkClient dingTalkClient,
                          OpenAiCompatibleVisionClient visionClient,
                          DashScopeMultimodalEmbedder imageEmbedderClient) {
        this.repository = repository;
        this.embeddingProbeClient = embeddingProbeClient;
        this.openAiCompatibleClient = openAiCompatibleClient;
        this.rerankClient = rerankClient;
        this.layoutClient = layoutClient;
        this.dingTalkClient = dingTalkClient;
        this.visionClient = visionClient;
        this.imageEmbedderClient = imageEmbedderClient;
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
                case GROUP_EMBEDDING -> embeddingProbeClient.probeEmbedding(cfg);
            case GROUP_LLM -> openAiCompatibleClient.probeChat(cfg);
            case GROUP_RERANK -> rerankClient.probe(cfg);
                case GROUP_LAYOUT -> layoutClient.probe(cfg);
                case GROUP_DINGTALK -> dingTalkClient.probe(cfg);
                case GROUP_VISION -> visionClient.probe(cfg);
                case GROUP_IMAGE_EMBEDDING -> imageEmbedderClient.probe(cfg);
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
