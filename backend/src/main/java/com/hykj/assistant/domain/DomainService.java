package com.hykj.assistant.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hykj.assistant.adapters.anythingllm.KernelGateway;
import com.hykj.assistant.ingest.DocumentRepository;
import com.hykj.assistant.retrieval.KnowledgeRetriever;
import com.hykj.assistant.security.AccessFilter;
import com.hykj.assistant.security.AccessSubject;
import com.hykj.assistant.retrieval.RetrievedPassage;
import com.hykj.assistant.settings.SettingService;

@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    public record ProvisionResult(String domainCode, String workspaceSlug, String action, String message) {
    }

    /**
     * 知识域视图。{@code visibleDocuments} 是**当前身份可见**的文档数（AC-P1-009），
     * 刻意不是文档总数——总数会顺带告诉调用方"这个域里还有你看不到的文档"。
     */
    public record DomainView(String code, String name, String workspaceSlug, String description,
                             boolean enabled, int visibleDocuments) {
    }

    public record CheckItem(String key, String title, String status, String detail) {
    }

    public record SelfCheckResult(boolean ok, Integer vectorCount,
                                  KernelGateway.KernelSnapshot kernel, List<CheckItem> items) {
    }

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String FAIL = "FAIL";

    private final DomainRepository repository;
    private final SettingService settingService;
    private final KernelGateway kernelGateway;
    private final DocumentRepository documentRepository;
    private final KnowledgeRetriever retriever;

    public DomainService(DomainRepository repository, SettingService settingService,
                         KernelGateway kernelGateway, DocumentRepository documentRepository,
                         KnowledgeRetriever retriever) {
        this.repository = repository;
        this.settingService = settingService;
        this.kernelGateway = kernelGateway;
        this.documentRepository = documentRepository;
        this.retriever = retriever;
    }

    /**
     * 列出知识域，并带上当前身份的可见文档数。
     *
     * <p>可见数只为"把看不见的域标出来"服务：它让用户不必先问一次被拒才发现没权限。
     * 它同时也是这份视图里唯一按身份变化的字段——域本身是全局配置。</p>
     */
    public List<DomainView> list(AccessSubject subject) {
        Map<String, Integer> visible = documentRepository.countVisibleByDomain(
                AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS));
        return repository.findAll().stream()
                .map(domain -> new DomainView(domain.code(), domain.name(), domain.workspaceSlug(),
                        domain.description(), domain.enabled(), visible.getOrDefault(domain.code(), 0)))
                .toList();
    }

    /**
     * 启动自检：内核可达 → 密钥可用 → 三个工作区齐备 → 两处 embedding 一致。
     * 只报告不抛异常：配置要靠人来改，服务起不来就没人能改。
     */
    public SelfCheckResult selfCheck() {
        Map<String, String> kernelCfg = settingService.effective(SettingService.GROUP_ANYTHINGLLM);
        Map<String, String> embeddingCfg = settingService.effective(SettingService.GROUP_EMBEDDING);
        String baseUrl = kernelCfg.getOrDefault("baseUrl", "");
        List<CheckItem> items = new ArrayList<>();

        boolean online = false;
        try {
            online = kernelGateway.online(kernelCfg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.debug("内核连通性探测失败：{}", ex.getMessage());
        }
        items.add(new CheckItem("kernel.reachable", "内核服务可达", online ? OK : FAIL,
                online ? baseUrl + " 在线" : baseUrl + " 不可达，请确认 AnythingLLM 容器已启动"));

        boolean hasKey = kernelGateway.hasApiKey(kernelCfg);
        items.add(new CheckItem("kernel.apiKey", "内核 API Key", hasKey ? OK : FAIL, hasKey
                ? "已配置"
                : "未配置，无法创建工作区、无法检索；AnythingLLM → 设置 → 工具 → 开发者 API 生成后填入"));

        KernelGateway.KernelSnapshot snapshot = null;
        Integer vectorCount = null;
        List<KnowledgeDomain> domains = repository.findAll();
        if (online && hasKey) {
            List<String> missing = new ArrayList<>();
            try {
                snapshot = kernelGateway.systemSnapshot(kernelCfg);
                vectorCount = kernelGateway.vectorCount(kernelCfg);
                Set<String> existing = new HashSet<>();
                kernelGateway.listWorkspaces(kernelCfg).forEach(workspace -> existing.add(workspace.slug()));
                for (KnowledgeDomain domain : domains) {
                    if (domain.enabled() && !existing.contains(domain.workspaceSlug())) {
                        missing.add(domain.workspaceSlug());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.debug("读取内核快照失败：{}", ex.getMessage());
            }
            items.add(new CheckItem("domain.workspaces", "知识域工作区", missing.isEmpty() ? OK : FAIL,
                    missing.isEmpty()
                            ? domains.size() + " 个工作区齐备"
                            : "内核缺少工作区：" + String.join("、", missing) + "；调用“初始化知识域”即可幂等创建"));
        }

        if (online && hasKey) {
            items.add(retrievalCheck(domains));
        }

        items.add(embeddingCheck(snapshot, embeddingCfg));

        List<String> failures = new ArrayList<>();
        for (CheckItem item : items) {
            if (FAIL.equals(item.status())) {
                failures.add(item.title() + "：" + item.detail());
            }
        }
        boolean ok = failures.isEmpty();
        if (!ok) {
            log.warn("内核自检未通过 —— {}", String.join(" | ", failures));
        }
        return new SelfCheckResult(ok, vectorCount, snapshot, items);
    }

    /**
     * 检索链路探针：拿库中一份现行文档的标题去问内核，验证两件事——"能搜到"和"搜到的文档名能反查回本地登记"。
     * 第二条是 T07 的生命线：命中片段只带得回内核文档名，反查不上就等于引用定位断链。
     */
    private CheckItem retrievalCheck(List<KnowledgeDomain> domains) {
        String title = "检索链路（" + retriever.displayName() + "）";
        List<DocumentRepository.Row> indexed = documentRepository.findAll().stream()
                .filter(row -> "INDEXED".equals(row.indexStatus())
                        && !blankToEmpty(row.kernelDocName()).isEmpty())
                .toList();
        if (indexed.isEmpty()) {
            return new CheckItem("retrieval.probe", title, WARN, "库中暂无已入库文档，跳过探测");
        }
        DocumentRepository.Row probe = indexed.stream()
                .filter(row -> row.docStatus() != null && row.docStatus().contains("现行"))
                .findFirst().orElse(indexed.get(0));
        String slug = domains.stream()
                .filter(domain -> domain.code().equals(probe.domainCode()))
                .map(KnowledgeDomain::workspaceSlug)
                .findFirst().orElse("");
        if (slug.isEmpty()) {
            return new CheckItem("retrieval.probe", title, WARN,
                    "未找到文档所属知识域 " + probe.domainCode() + " 的工作区，跳过探测");
        }
        try {
            // 自建通道要求带上知识域与可见范围（它在 SQL 层就按权限过滤），
            // 所以探测统一按"系统管理员身份 + 全可见"来构造，两种通道都能跑。
            AccessFilter filter = AccessFilter.of(
                    AccessSubject.of("__selfcheck__", "admin", null), AccessFilter.DEFAULT_ALIAS);
            List<RetrievedPassage> hits = retriever.search(new KnowledgeRetriever.Query(slug,
                    probe.domainCode(), filter, probe.title(), 3, 0d));
            if (hits.isEmpty()) {
                return new CheckItem("retrieval.probe", title, WARN,
                        slug + " 检索返回 0 条，工作区可能为空，或向量化与后端配置不一致");
            }
            Set<String> known = indexed.stream().map(DocumentRepository.Row::kernelDocName)
                    .collect(Collectors.toSet());
            long resolvable = hits.stream().filter(hit -> known.contains(hit.kernelDocName())).count();
            RetrievedPassage top = hits.get(0);
            String detail = "%s 以「%s」探测：命中 %d 条，%d 条可反查本地文档，最高分 %.2f"
                    .formatted(slug, probe.title(), hits.size(), resolvable, top.score());
            if (top.text().isBlank() || top.text().contains("<document_metadata>")) {
                return new CheckItem("retrieval.probe", title, FAIL,
                        detail + "；但片段正文没洗干净（内核元数据块残留），引用会带出噪声");
            }
            if (resolvable == 0) {
                return new CheckItem("retrieval.probe", title, WARN,
                        detail + "；命中文件名在本地登记里找不到，内核里可能混有未登记的文件");
            }
            return new CheckItem("retrieval.probe", title, OK, detail);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CheckItem("retrieval.probe", title, WARN, "检索探测被中断");
        } catch (Exception ex) {
            return new CheckItem("retrieval.probe", title, FAIL, "检索失败：" + ex.getMessage());
        }
    }

    /**
     * 内核是唯一建索引的地方，查询向量也由它生成，所以内核内部是自洽的；
     * 但后端一旦自己去向量化（重排、缓存、迁移到 pgvector），模型不一致就会静默检索错。
     */
    private CheckItem embeddingCheck(KernelGateway.KernelSnapshot snapshot, Map<String, String> cfg) {
        String title = "向量化模型一致性";
        if (snapshot == null) {
            return new CheckItem("embedding.consistency", title, WARN, "未取到内核向量化配置，跳过比对");
        }
        String engine = snapshot.embeddingEngine();
        String kernelModel = snapshot.embeddingModel();
        String kernelBase = trimSlash(snapshot.embeddingBasePath());
        String localModel = blankToEmpty(cfg.get("model"));
        String localBase = trimSlash(cfg.get("baseUrl"));
        if (!"generic-openai".equalsIgnoreCase(engine)) {
            return new CheckItem("embedding.consistency", title, FAIL,
                    "内核用 " + engine + " 引擎（模型 " + kernelModel + "），后端配的是 " + localModel
                            + "；两者不是同一个模型，向量不可比。更换内核向量化模型会导致全部索引作废");
        }
        if (kernelModel.equals(localModel) && kernelBase.equals(localBase)) {
            return new CheckItem("embedding.consistency", title, OK,
                    "内核与后端一致：" + localModel + " @ " + localBase);
        }
        return new CheckItem("embedding.consistency", title, FAIL,
                "内核为 " + kernelModel + " @ " + kernelBase + "，后端为 " + localModel + " @ " + localBase
                        + "；两处必须一致");
    }

    private static String trimSlash(String value) {
        String trimmed = blankToEmpty(value);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** 幂等：已存在的工作区不重复创建。逐域复用 {@link #provisionOne}。 */
    public List<ProvisionResult> provision() {
        return repository.findAll().stream().map(domain -> provisionOne(domain.code())).toList();
    }

    /** 单个域的工作区初始化（幂等）。 */
    public ProvisionResult provisionOne(String code) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_ANYTHINGLLM);
        if (!kernelGateway.hasApiKey(cfg)) {
            return new ProvisionResult(code, domain.workspaceSlug(), "SKIPPED",
                    "未配置 AnythingLLM Developer API Key，无法创建工作区");
        }
        try {
            Set<String> existing = new HashSet<>();
            kernelGateway.listWorkspaces(cfg).forEach(workspace -> existing.add(workspace.slug()));
            if (existing.contains(domain.workspaceSlug())) {
                return new ProvisionResult(code, domain.workspaceSlug(), "EXISTS", "工作区已存在");
            }
            // 用 slug 当创建名：中文名会被内核 slugify 成空串，退化成随机 uuid，之后就找不回来了
            var created = kernelGateway.createWorkspace(cfg, domain.workspaceSlug());
            String slug = created.slug();
            if (!domain.name().equals(domain.workspaceSlug())) {
                kernelGateway.updateWorkspace(cfg, slug, Map.of("name", domain.name()));
            }
            String note = "";
            if (!domain.workspaceSlug().equals(slug)) {
                repository.updateWorkspaceSlug(code, slug);
                note = "；内核实际 slug=" + slug + "，已回写 knowledge_domain";
            }
            log.info("已创建工作区：{} (slug={})", domain.name(), slug);
            return new ProvisionResult(code, slug, "CREATED", "工作区已创建，显示名 " + domain.name() + note);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProvisionResult(code, domain.workspaceSlug(), "FAILED", "创建被中断");
        } catch (Exception ex) {
            return new ProvisionResult(code, domain.workspaceSlug(), "FAILED", "创建失败：" + ex.getMessage());
        }
    }

    /**
     * 新建知识域。**只落本地登记，不建内核工作区**——工作区初始化是显式的单独动作
     * （`POST /domains/{code}/provision`，界面上是行内「初始化工作区」）。
     *
     * <p>为什么不在建时顺手建工作区：建工作区打到内核、有副作用，而"先建域、确认无误后再初始化"
     * 让半成品状态可控可查（自检会报"缺工作区"），也避免建域动作凭空在内核里留下东西。</p>
     */
    public DomainView create(String code, String name, String workspaceSlug, String description, Boolean enabled) {
        String normalized = requireCode(code);
        String title = requireText(name, "知识域名称不能为空");
        if (repository.findByCode(normalized).isPresent()) {
            throw new IllegalArgumentException("知识域已存在：" + normalized);
        }
        String slug = workspaceSlug == null || workspaceSlug.isBlank() ? "knowledge-" + normalized
                : requireText(workspaceSlug, "工作区 slug 不合法");
        if (repository.existsWorkspaceSlug(slug, null)) {
            throw new IllegalArgumentException("工作区 slug 已被占用：" + slug);
        }
        String desc = description == null ? null : description.trim();
        repository.insert(normalized, title, slug, desc, repository.maxSortOrder() + 1);
        log.info("已创建知识域 {}（{}，slug={}）；工作区待初始化", normalized, title, slug);
        // enabled 参数当前只在更新时生效（新建恒为启用）；保留参数以免调用方以为能传
        return new DomainView(normalized, title, slug, desc, true, 0);
    }

    public DomainView update(String code, String name, String description, Boolean enabled) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        String newName = name == null || name.isBlank() ? domain.name() : name.trim();
        String newDesc = description == null ? domain.description() : description.trim();
        boolean newEnabled = enabled == null ? domain.enabled() : enabled;
        repository.update(code, newName, newDesc, newEnabled);
        return new DomainView(code, newName, domain.workspaceSlug(), newDesc, newEnabled, 0);
    }

    /**
     * 删除知识域。**有文档就拒绝**——文档行有外键指向它，且删除会牵动内核工作区归属。
     * 内核工作区**不删**（避免误删内核里我们管不到的数据），只删本地登记；要清理请在内核侧操作。
     */
    public void delete(String code) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        int documents = documentRepository.countByDomain(code);
        if (documents > 0) {
            throw new IllegalStateException("知识域下还有 " + documents + " 份文档，请先清理或改为停用");
        }
        repository.delete(code);
        log.info("已删除知识域 {}；内核工作区 {} 未删除（如需清理请在内核侧操作）", code, domain.workspaceSlug());
    }

    /** code 是 URL 路径与文档域字段的一部分，限小写字母数字与 -_，长度 2~32。 */
    private static String requireCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        if (!normalized.matches("[a-z][a-z0-9_-]{1,31}")) {
            throw new IllegalArgumentException("知识域代码只允许小写字母/数字/-/_，字母开头，长度 2~32：" + code);
        }
        return normalized;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
