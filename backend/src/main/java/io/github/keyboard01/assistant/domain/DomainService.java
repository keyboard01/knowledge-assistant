package io.github.keyboard01.assistant.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import io.github.keyboard01.assistant.settings.SettingService;

@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    /**
     * 知识域视图。{@code visibleDocuments} 是**当前身份可见**的文档数（AC-P1-009），
     * 刻意不是文档总数——总数会顺带告诉调用方"这个域里还有你看不到的文档"。
     */
    public record DomainView(String code, String name, String description, boolean enabled, int visibleDocuments) {
    }

    public record CheckItem(String key, String title, String status, String detail) {
    }

    public record SelfCheckResult(boolean ok, List<CheckItem> items) {
    }

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String FAIL = "FAIL";

    private final DomainRepository repository;
    private final SettingService settingService;
    private final DocumentRepository documentRepository;
    private final KnowledgeRetriever retriever;
    private final DomainAclRepository domainAclRepository;
    private final AssetStore assetStore;

    public DomainService(DomainRepository repository, SettingService settingService,
                         DocumentRepository documentRepository, KnowledgeRetriever retriever,
                         DomainAclRepository domainAclRepository, AssetStore assetStore) {
        this.repository = repository;
        this.settingService = settingService;
        this.documentRepository = documentRepository;
        this.retriever = retriever;
        this.domainAclRepository = domainAclRepository;
        this.assetStore = assetStore;
    }

    /**
     * 列出**当前身份可见**的知识域，并带上当前身份的可见文档数。
     *
     * <p>两道门都要过：先域级授权（{@code domain_acl}，admin 短路放行），再过文档级 ACL。
     * 域级过滤必须在这里做——否则用户能从一个"无权访问的域"里读到可见文档数，等于泄露它有多少内容。
     * 可见数只为"把看不见的域标出来"服务。</p>
     */
    public List<DomainView> list(AccessSubject subject) {
        Map<String, Integer> visible = documentRepository.countVisibleByDomain(
                AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS));
        List<KnowledgeDomain> all = repository.findAll();
        Map<String, List<AclRule>> grants = domainAclRepository.findByDomainCodes(
                all.stream().map(KnowledgeDomain::code).toList());
        boolean admin = isAdmin(subject);
        return all.stream()
                .filter(domain -> admin || AclRule.grantsAny(subject, grants.get(domain.code())))
                .map(domain -> new DomainView(domain.code(), domain.name(),
                        domain.description(), domain.enabled(), visible.getOrDefault(domain.code(), 0)))
                .toList();
    }

    /** 域级可见性：admin 一律可见（否则会把自己锁在私有域外），其余按 domain_acl 命中。 */
    public boolean canAccess(AccessSubject subject, String domainCode) {
        if (isAdmin(subject)) {
            return true;
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

    public List<AclRule> listAcl(String code) {
        if (repository.findByCode(code).isEmpty()) {
            throw new IllegalArgumentException("知识域不存在：" + code);
        }
        return domainAclRepository.findByDomainCode(code);
    }

    /** 整份替换某域的授权（admin）。留空即"只有 admin 可见"。 */
    public List<AclRule> replaceAcl(String code, List<AclRule> rules) {
        if (repository.findByCode(code).isEmpty()) {
            throw new IllegalArgumentException("知识域不存在：" + code);
        }
        domainAclRepository.replace(code, rules);
        log.info("已更新知识域授权 {}：{} 条规则", code, rules == null ? 0 : rules.size());
        return domainAclRepository.findByDomainCode(code);
    }

    private static boolean isAdmin(AccessSubject subject) {
        return subject.roles().stream().anyMatch(role -> "admin".equalsIgnoreCase(role));
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

    /** 新建知识域（admin）。 */
    public DomainView create(String code, String name, String description, Boolean enabled) {
        String normalized = requireCode(code);
        String title = requireText(name, "知识域名称不能为空");
        if (repository.findByCode(normalized).isPresent()) {
            throw new IllegalArgumentException("知识域已存在：" + normalized);
        }
        String desc = description == null ? null : description.trim();
        repository.insert(normalized, title, desc, repository.maxSortOrder() + 1);
        // 新建域默认公开：写一行 ALL:* 授权。否则 fail-closed 下新域对所有人都不可见。
        domainAclRepository.replace(normalized, List.of(new AclRule("ALL", "*", AclRule.READ)));
        log.info("已创建知识域 {}（{}）", normalized, title);
        // enabled 参数当前只在更新时生效（新建恒为启用）
        return new DomainView(normalized, title, desc, true, 0);
    }

    public DomainView update(String code, String name, String description, Boolean enabled) {
        KnowledgeDomain domain = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        String newName = name == null || name.isBlank() ? domain.name() : name.trim();
        String newDesc = description == null ? domain.description() : description.trim();
        boolean newEnabled = enabled == null ? domain.enabled() : enabled;
        repository.update(code, newName, newDesc, newEnabled);
        return new DomainView(code, newName, newDesc, newEnabled, 0);
    }

    /** 删除知识域。**有文档就拒绝**——文档行有外键指向它。 */
    public void delete(String code) {
        repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("知识域不存在：" + code));
        int documents = documentRepository.countByDomain(code);
        if (documents > 0) {
            throw new IllegalStateException("知识域下还有 " + documents + " 份文档，请先清理或改为停用");
        }
        repository.delete(code);
        log.info("已删除知识域 {}", code);
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
