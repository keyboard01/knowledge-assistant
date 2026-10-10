package io.github.keyboard01.assistant.adapters.anythingllm;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

/**
 * 内核（AnythingLLM）的**管理与生命周期**出口（A1）。
 *
 * <p>在此之前，"只有 {@code adapters/anythingllm} 这个包碰内核"只是一条**约定**：
 * {@code IngestService}、{@code DomainService}、{@code SettingService} 直接构造注入了
 * {@link AnythingLlmClient} 这个具体类，于是"换内核只改一处"实际上不成立——真要换，这三个类也得跟着改。
 * 把外部真正用到的那几个能力抽到这个接口上之后，这条边界从"读代码时自觉遵守"变成"编译器帮忙守"。</p>
 *
 * <p>范围刻意只有"管理面"：工作区开箱、文档上传/删除、系统快照与向量计数。**检索面不在这里**——
 * 业务层要检索时依赖的是 {@code retrieval/KnowledgeRetriever}，内核实现只是它的一个实现类，
 * 两条边界不合并（合并了就等于宣布"检索必须经由 kernel 的管理接口"，那正是 P2 收回去的东西）。</p>
 *
 * <p>方法签名里的 {@code Map<String, String> cfg} 是"当时生效的配置"，由调用方的
 * {@code SettingService} 取好传进来：接口不自己读配置，注入的替身才好造。</p>
 */
public interface KernelGateway {

    /** 内核工作区。slug 是寻址用的稳定键，name 是可改的显示名。 */
    record Workspace(String name, String slug) {
    }

    /** 上传成功后内核给的登记信息；{@code location} 是之后删除它要用的凭据。 */
    record UploadedDocument(String location, String title) {
    }

    /**
     * 内核侧现状快照。GET /api/v1/system 是当前版本唯一能读到内核向量化配置的接口，
     * "内核与后端两处 embedding 必须一致"这条不变量靠它校验。
     */
    record KernelSnapshot(String embeddingEngine, String embeddingBasePath, String embeddingModel,
                          String embeddingDimensions, boolean embeddingApiKeyConfigured,
                          boolean hasExistingEmbeddings, String llmProvider, String llmModel) {
    }

    /** 是否配了 Developer API Key。没配就只能访问公开接口，业务上等于不可用。 */
    boolean hasApiKey(Map<String, String> cfg);

    /** 只检查服务是否在线，不做鉴权。 */
    boolean online(Map<String, String> cfg) throws IOException, InterruptedException;

    List<Workspace> listWorkspaces(Map<String, String> cfg) throws IOException, InterruptedException;

    /** 用英文 slug 当创建名：中文名会被内核 slugify 成空串并退化成随机 uuid，之后就找不回来了。 */
    Workspace createWorkspace(Map<String, String> cfg, String name) throws IOException, InterruptedException;

    /** 只改显示名。内核的可写字段白名单里没有 slug，改名不会动摇寻址。 */
    void updateWorkspace(Map<String, String> cfg, String slug, Map<String, Object> updates)
            throws IOException, InterruptedException;

    /**
     * 上传即入库：内核在同一个请求里完成解析、切分与向量化，响应返回时就能检索到。
     * {@code fileName} 会成为内核里的文档名，必须用 ASCII——检索命中要靠它反查本地文档。
     */
    UploadedDocument uploadDocument(Map<String, String> cfg, String workspaceSlug, String fileName,
                                    String contentType, byte[] content, Map<String, String> metadata)
            throws IOException, InterruptedException;

    void removeDocuments(Map<String, String> cfg, List<String> locations)
            throws IOException, InterruptedException;

    KernelSnapshot systemSnapshot(Map<String, String> cfg) throws IOException, InterruptedException;

    Integer vectorCount(Map<String, String> cfg) throws IOException, InterruptedException;

    /** 设置页的"测试连接"：返回人能看懂的一句话，不抛异常。 */
    TestResult probe(Map<String, String> cfg);
}