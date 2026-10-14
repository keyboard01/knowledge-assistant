package io.github.keyboard01.assistant.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.keyboard01.assistant.ingest.DocumentRepository;
import io.github.keyboard01.assistant.media.AssetStore;
import io.github.keyboard01.assistant.retrieval.KnowledgeRetriever;
import io.github.keyboard01.assistant.retrieval.RetrievedPassage;
import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.AclRule;
import io.github.keyboard01.assistant.security.DocumentVisibility;
import io.github.keyboard01.assistant.security.DomainAclRepository;
import io.github.keyboard01.assistant.security.TenantRule;
import io.github.keyboard01.assistant.settings.SettingService;
import io.github.keyboard01.assistant.tenant.TenantRepository;

@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    /**
     * 知识域视图。{@code visibleDocuments} 是**当前身份可见**的文档数（AC-P1-009），
     * 刻意不是文档总数——总数会顺带告诉调用方"这个域里还有你看不到的文档"。
     *
     * <p>{@code tenantCode} 是它所属的租户：目前只用于展示与"建域时选租户"，
     * 可见性作用域还没按它收窄（那是多项目接入的下一步，见 docs/多项目接入方案.md）。</p>
     */
    public record DomainView(String code, String name, String description, boolean enabled, int visibleDocuments,
                             String tenantCode) {
    }

    public record CheckItem(String key, String title, String status, String detail) {
    }

    public record SelfCheckResult(boolean ok, List<CheckItem> items) {
    }

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String FAIL = "FAIL";

    /** 默认租户：V2 迁移建的那一个，升级前就存在的域都归它；建域不指定租户时也落这里。 */
    static final String DEFAULT_TENANT = "default";

    private final DomainRepository repository;
    private final SettingService settingService;
    private final DocumentRepository documentRepository;
    private final KnowledgeRetriever retriever;
    private final DomainAclRepository domainAclRepository;
    private final AssetStore assetStore;
    private final TenantRepository tenantRepository;

    public DomainService(DomainRepository repository, SettingService settingService,
                         DocumentRepository documentRepository, KnowledgeRetriever retriever,
                         DomainAclRepository domainAclRepository, AssetStore assetStore,
                         TenantRepository tenantRepository) {
        this.repository = repository;
        this.settingService = settingService;
        this.documentRepository = documentRepository;
        this.retriever = retriever;
        this.domainAclRepository = domainAclRepository;
        this.assetStore = assetStore;
        this.tenantRepository = tenantRepository;
    }

    /**
     * 列出**当前身份可见**的知识域，并带上当前身份的可见文档数。
     *
     * <p>两道门都要过：先域级授权（{@code domain_acl}，admin 短路放行）**加租户范围**，
     * 再过文档级 ACL。域级过滤必须在这里做——否则用户能从一个"无权访问的域"里读到可见文档数，
     * 等于泄露它有多少内容。可见数只为"把看不见的域标出来"服务。</p>
     *
     * <p>租户范围在这里判而不是在 SQL 里：域列表本来就全量载入内存，
     * 逐个域查一次租户归属没有意义，所以租户集合只查一次（见 {@link TenantRule#allows}）。</p>
     */
    public List<DomainView> list(AccessSubject subject) {
        Map<String, Integer> visible = documentRepository.countVisibleByDomain(
                AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS));
        List<KnowledgeDomain> all = repository.findAll();
        Map<String, List<AclRule>> grants = domainAclRepository.findByDomainCodes(
                all.stream().map(KnowledgeDomain::code).toList());
        boolean admin = TenantRule.isPlatformAdmin(subject);
        Set<String> myTenants = admin ? Set.of() : tenantRepository.tenantsOf(subject.username());
        return all.stream()
                .filter(domain -> admin
                        || (TenantRule.allows(subject, domain.tenantCode(), myTenants)
                        && AclRule.grantsAny(subject, grants.get(domain.code()))))
                .map(domain -> new DomainView(domain.code(), domain.name(), domain.description(),
                        domain.enabled(), visible.getOrDefault(domain.code(), 0), domain.tenantCode()))
                .toList();
    }

    /**
     * 域级可见性：**平台管理员**一律可见（否则会把自己锁在私有域外），
     * 其余人必须同时满足"域在他的租户里"（{@link TenantRule}）与"域授权命中"。
     *
     * <p>判租户归属要读一次域行与一次成员关系；这条路径每次检索/查看/找图都会走，
     * 但正确性优先——漏判这一条就是跨租户泄漏。</p>
     */
    public boolean canAccess(AccessSubject subject, String domainCode) {
        if (TenantRule.isPlatformAdmin(subject)) {
            return true;
        }
        KnowledgeDomain domain = repository.findByCode(domainCode).orElse(null);
        if (domain == null) {
            return false;
        }
        if (!TenantRule.allows(subject, domain.tenantCode(), tenantRepository.tenantsOf(subject.username()))) {
            return false;
        }
        return AclRule.grantsAny(subject, domainAclRepository.findByDomainCode(domainCode));
    }

    /**
     * 单一守卫：检索、查看、上传都先过这里，避免将来新增入口漏判。
     * 域不存在与"无权访问"返回同一种拒绝，不暴露存在性。
     */
    public void requireAccessible(AccessSubject subject, String domainCode) {
        if (!canAccess(subject, domainCode)) {
            throw new IllegalArgumentException("无权访问该知识域：" + domainCode);
        }
    }

    public List<AclRule> listAcl(AccessSubject subject, String code) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        requireAdminister(subject, domain.tenantCode(), "查看知识域 " + code + " 的授权");
        return domainAclRepository.findByDomainCode(code);
    }

    /** 整份替换某域的授权。留空即"只有管理员可见"。 */
    public List<AclRule> replaceAcl(AccessSubject subject, String code, List<AclRule> rules) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        requireAdminister(subject, domain.tenantCode(), "修改知识域 " + code + " 的授权");
        domainAclRepository.replace(code, rules);
        log.info("已更新知识域授权 {}：{} 条规则", code, rules == null ? 0 : rules.size());
        return domainAclRepository.findByDomainCode(code);
    }

    /**
     * 本地自检：文档根目录可读 → 图片资产目录合法 → 本地检索链路 → 向量化配置。
     * 只报告不抛异常：配置要靠人来改，服务起不来就没人能改。
     */
    public SelfCheckResult selfCheck() {
        List<CheckItem> items = new ArrayList<>();
        items.add(documentRootCheck());
        items.add(assetRootCheck());
        items.add(embeddingCheck());
        items.add(retrievalCheck());

        List<String> failures = new ArrayList<>();
        for (CheckItem item : items) {
            if (FAIL.equals(item.status())) {
                failures.add(item.title() + "：" + item.detail());
            }
        }
        boolean ok = failures.isEmpty();
        if (!ok) {
            log.warn("自检未通过 —— {}", String.join(" | ", failures));
        }
        return new SelfCheckResult(ok, items);
    }

    private CheckItem documentRootCheck() {
        String title = "文档根目录";
        Path root = resolveIngestPath("sourceRoot", "knowledge");
        if (Files.isDirectory(root) && Files.isReadable(root)) {
            return new CheckItem("ingest.sourceRoot", title, OK, root.toString());
        }
        return new CheckItem("ingest.sourceRoot", title, FAIL, root + " 不存在或不可读");
    }

    private CheckItem assetRootCheck() {
        String title = "图片资产目录";
        try {
            return new CheckItem("media.assetRoot", title, OK, assetStore.root().toString());
        } catch (RuntimeException ex) {
            return new CheckItem("media.assetRoot", title, FAIL, ex.getMessage());
        }
    }

    /** 本地向量化是一切检索的前提：地址与模型名没配好，片段就建不出来。 */
    private CheckItem embeddingCheck() {
        String title = "向量化配置";
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_EMBEDDING);
        String baseUrl = blankToEmpty(cfg.get("baseUrl"));
        String model = blankToEmpty(cfg.get("model"));
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return new CheckItem("embedding.config", title, FAIL,
                    "未配置向量化接口地址或模型名（系统配置 → 向量化 Embedding）");
        }
        return new CheckItem("embedding.config", title, OK, model + " @ " + baseUrl);
    }

    /**
     * 检索链路探针：拿库中一份片段就绪的文档标题去检索，验证"能搜到"且"搜到的就是这份文档"。
     */
    private CheckItem retrievalCheck() {
        String title = "检索链路（" + retriever.displayName() + "）";
        List<DocumentRepository.Row> ready = documentRepository.findAll().stream()
                .filter(row -> "INDEXED".equals(row.chunkStatus()))
                .toList();
        if (ready.isEmpty()) {
            return new CheckItem("retrieval.probe", title, WARN, "库中暂无片段就绪的文档，跳过探测");
        }
        DocumentRepository.Row probe = ready.stream()
                .filter(row -> DocumentVisibility.isCurrent(row.docStatus()))
                .findFirst().orElse(ready.get(0));
        try {
            // 按"系统管理员身份 + 全可见"构造，避免探针被权限挡下
            AccessFilter filter = AccessFilter.of(
                    AccessSubject.of("__selfcheck__", "admin", null), AccessFilter.DEFAULT_ALIAS);
            List<RetrievedPassage> hits = retriever.search(
                    new KnowledgeRetriever.Query(probe.domainCode(), filter, probe.title(), 3, 0d));
            if (hits.isEmpty()) {
                return new CheckItem("retrieval.probe", title, WARN,
                        probe.domainCode() + " 检索返回 0 条，片段可能为空，或向量化配置不一致");
            }
            long resolvable = hits.stream().filter(hit -> hit.documentId() == probe.id()).count();
            RetrievedPassage top = hits.get(0);
            String detail = "以「%s」探测：命中 %d 条，最高分 %.2f"
                    .formatted(probe.title(), hits.size(), top.score());
            if (resolvable == 0) {
                return new CheckItem("retrieval.probe", title, WARN, detail + "；但命中不含该文档本身，请检查分块与向量");
            }
            return new CheckItem("retrieval.probe", title, OK, detail);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CheckItem("retrieval.probe", title, WARN, "检索探测被中断");
        } catch (Exception ex) {
            return new CheckItem("retrieval.probe", title, FAIL, "检索失败：" + ex.getMessage());
        }
    }

    private Path resolveIngestPath(String key, String fallback) {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_INGEST);
        String configured = cfg.get(key);
        String value = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 管理权（多项目接入 S3）：平台管理员管全部；租户管理员只能管自己租户的域。
     *
     * <p>这是"租户管理员"这一角色的核心判定，域 CRUD / 域授权 / 上传审核三处都走它——
     * 不能各写一遍，否则某一条漏判就是跨租户越权。</p>
     */
    public boolean canAdminister(AccessSubject subject, String tenantCode) {
        if (TenantRule.isPlatformAdmin(subject)) {
            return true;
        }
        return tenantRepository.isTenantAdmin(subject.username(), tenantCode);
    }

    /**
     * 新建知识域。
     *
     * <p><b>租户作用域</b>：平台管理员可指定任意租户（留空落默认租户）；租户管理员只能建在
     * 自己管理的租户里（留空时若只管理一个租户就自动用那一个，管理多个则必须显式指定；
     * 指定了非自己管理的租户 → 拒绝）。</p>
     */
    public DomainView create(AccessSubject subject, String code, String name, String description, Boolean enabled,
                             String tenantCode) {
        String normalized = requireCode(code);
        String title = requireText(name, "知识域名称不能为空");
        if (repository.findByCode(normalized).isPresent()) {
            throw new IllegalArgumentException("知识域已存在：" + normalized);
        }
        String tenant = resolveTenant(subject, tenantCode);
        String desc = description == null ? null : description.trim();
        repository.insert(normalized, title, desc, repository.maxSortOrder() + 1, tenant, subject.username());
        // 新建域默认公开：写一行 ALL:* 授权。否则 fail-closed 下新域对所有人都不可见。
        domainAclRepository.replace(normalized, List.of(new AclRule("ALL", "*", AclRule.READ)));
        log.info("已创建知识域 {}（{}），租户 {}，创建者 {}", normalized, title, tenant, subject.username());
        // enabled 参数当前只在更新时生效（新建恒为启用）
        return new DomainView(normalized, title, desc, true, 0, tenant);
    }

    /** 定新建域落在哪个租户：平台管理员可任意，租户管理员锁定在自己管理的租户。 */
    private String resolveTenant(AccessSubject subject, String tenantCode) {
        String specified = tenantCode == null ? "" : tenantCode.trim();
        if (TenantRule.isPlatformAdmin(subject)) {
            String tenant = specified.isEmpty() ? DEFAULT_TENANT : specified;
            if (!repository.tenantExists(tenant)) {
                throw new IllegalArgumentException("租户不存在：" + tenant);
            }
            return tenant;
        }
        // 非平台管理员 = 只可能是租户管理员（普通用户连域级门都进不来）
        Set<String> administered = tenantRepository.administeredTenants(subject.username());
        String tenant = specified;
        if (tenant.isEmpty()) {
            if (administered.size() == 1) {
                tenant = administered.iterator().next();
            } else {
                throw new IllegalArgumentException("请指定知识域所属租户");
            }
        }
        if (!administered.contains(tenant)) {
            throw new IllegalArgumentException("无权在该租户下创建知识域：" + tenant);
        }
        return tenant;
    }

    public DomainView update(AccessSubject subject, String code, String name, String description, Boolean enabled) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        requireAdminister(subject, domain.tenantCode(), "管理知识域 " + code);
        String newName = name == null || name.isBlank() ? domain.name() : name.trim();
        String newDesc = description == null ? domain.description() : description.trim();
        boolean newEnabled = enabled == null ? domain.enabled() : enabled;
        repository.update(code, newName, newDesc, newEnabled);
        return new DomainView(code, newName, newDesc, newEnabled, 0, domain.tenantCode());
    }

    /** 删除知识域。**有文档就拒绝**——文档行有外键指向它。 */
    public void delete(AccessSubject subject, String code) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        requireAdminister(subject, domain.tenantCode(), "删除知识域 " + code);
        int documents = documentRepository.countByDomain(code);
        if (documents > 0) {
            throw new IllegalStateException("知识域下还有 " + documents + " 份文档，请先清理或改为停用");
        }
        repository.delete(code);
        log.info("已删除知识域 {}", code);
    }

    /** 管理权守卫：没有就抛可读的 400，与"域不存在与无权同形"的既有口径一致（不暴露更多信息）。 */
    private void requireAdminister(AccessSubject subject, String tenantCode, String action) {
        if (!canAdminister(subject, tenantCode)) {
            throw new IllegalArgumentException("无权" + action);
        }
    }

    /** 按域代码的管理权守卫（审核文档用：从域反查租户再判）。 */
    public void requireAdministerDomain(AccessSubject subject, String domainCode, String action) {
        KnowledgeDomain domain = repository.findByCode(domainCode)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + domainCode));
        requireAdminister(subject, domain.tenantCode(), action);
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
