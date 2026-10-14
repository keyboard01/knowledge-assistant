package io.github.keyboard01.assistant.observe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.keyboard01.assistant.adapters.HttpSupport;
import io.github.keyboard01.assistant.generation.GeneratedAnswer;
import io.github.keyboard01.assistant.generation.GenerationService;
import io.github.keyboard01.assistant.observe.EvalModels.Case;
import io.github.keyboard01.assistant.observe.EvalModels.CaseResult;
import io.github.keyboard01.assistant.observe.EvalModels.Metrics;
import io.github.keyboard01.assistant.observe.EvalModels.RunDetail;
import io.github.keyboard01.assistant.observe.EvalModels.RunRow;
import io.github.keyboard01.assistant.retrieval.Evidence;
import io.github.keyboard01.assistant.retrieval.RetrievalResult;
import io.github.keyboard01.assistant.retrieval.RetrievalService;
import io.github.keyboard01.assistant.security.AccessSubject;
import io.github.keyboard01.assistant.security.AppUser;
import io.github.keyboard01.assistant.security.UserRepository;
import io.github.keyboard01.assistant.settings.SettingService;

/**
 * 离线评测（P3）：把题集跑一遍，指标落库，供趋势与回归门使用。
 *
 * <p><b>为什么走服务层而不是打 HTTP。</b>一是能拿到 traceId、逐题的名次与分数，二是能做
 * {@code retrieval} 模式——只跑检索、不调生成模型，秒级且不花钱。黑盒脚本做不到这两件事，
 * 所以它适合当"验收"，这里适合当"仪表"。</p>
 *
 * <p><b>用的是真实身份与真实权限。</b>每道题按题集里的 {@code asker} 取用户，构造它自己的
 * {@link AccessSubject} 传进检索/生成（不碰 {@code CurrentUser} 那个 ThreadLocal，
 * 那是调用者的身份）。权限题因此走的就是线上那条路径，不会因为"评测"而放宽。</p>
 *
 * <p><b>先落 RUNNING 再逐题回填。</b>full 模式跑 30 题要几分钟，中途超时或被打断时，
 * 至少能看到跑到第几题、已完成的题对错如何——比"什么都没有"有用得多。</p>
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    /** 与既有验收脚本一致：题集按 topN=5 问。 */
    private static final int TOP_N = 5;

    static final String MODE_RETRIEVAL = "retrieval";
    static final String MODE_FULL = "full";

    private final EvalRepository runs;
    private final SettingService settings;
    private final UserRepository users;
    private final RetrievalService retrieval;
    private final GenerationService generation;
    private final ObserveService observe;

    public EvalService(EvalRepository runs, SettingService settings, UserRepository users,
                       RetrievalService retrieval, GenerationService generation, ObserveService observe) {
        this.runs = runs;
        this.settings = settings;
        this.users = users;
        this.retrieval = retrieval;
        this.generation = generation;
        this.observe = observe;
    }

    /**
     * 跑一次题集。
     *
     * <p>同步执行：{@code retrieval} 模式几秒，{@code full} 模式几分钟。中途失败会把 run 标成 FAILED
     * 再抛出，已写入的用例保留。</p>
     */
    public RunDetail run(String setName, String mode, AppUser runner) throws IOException, InterruptedException {
        String effectiveMode = normalizeMode(mode);
        List<Case> cases = loadCases(setName);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("题集里没有题目：" + setName);
        }
        long runId = runs.insertRun(runner.username(), setName, effectiveMode);
        log.info("[评测] 开始 run={} 题集={} 模式={} 题数={}", runId, setName, effectiveMode, cases.size());
        try {
            List<CaseResult> results = new ArrayList<>(cases.size());
            long promptTokens = 0;
            long completionTokens = 0;
            BigDecimal cost = null;
            for (Case item : cases) {
                CaseResult result = execute(item, effectiveMode);
                results.add(result);
                runs.insertCase(runId, result);
                if (result.traceId() != null) {
                    // full 模式的 token/费用直接从账本取：与账本用同一套口径，不另算一份
                    ObserveService.CostLine line = observe.costLine(result.traceId());
                    promptTokens += line.promptTokens();
                    completionTokens += line.completionTokens();
                    cost = add(cost, line.costTotal());
                }
            }
            Metrics metrics = EvalMetrics.summarize(results, promptTokens, completionTokens, cost);
            runs.finishRun(runId, metrics);
            log.info("[评测] 完成 run={} 通过 {}/{} hit@k={} mrr={} 泄露={}", runId, metrics.passed(),
                    metrics.passed() + metrics.failed(), metrics.hitAtK(), metrics.mrr(), metrics.leakCount());
        } catch (RuntimeException ex) {
            runs.failRun(runId, ex.getMessage());
            throw ex;
        }
        return detail(runId).orElseThrow(() -> new IllegalStateException("评测结果写库失败：" + runId));
    }

    public List<RunRow> recent(int limit, String setName) {
        return runs.recent(Math.min(Math.max(limit, 1), 200), setName);
    }

    public Optional<RunDetail> detail(long runId) {
        return runs.findRun(runId).map(row -> new RunDetail(row, runs.cases(runId)));
    }

    /** 可用题集：目录下的 .json 文件名（同时也是文件名白名单，防路径穿越）。 */
    public List<String> questionSets() {
        Path dir = questionDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("[评测] 题集目录读不出来：{} —— {}", dir, ex.getMessage());
            return List.of();
        }
    }

    private CaseResult execute(Case item, String mode) throws IOException, InterruptedException {
        AppUser asker = users.findByUsername(item.asker())
                .orElseThrow(() -> new IllegalArgumentException("题集里的提问人不存在：" + item.asker()));
        AccessSubject subject = asker.subject();
        long startedAt = System.nanoTime();
        try {
            if (MODE_RETRIEVAL.equals(mode)) {
                RetrievalResult found = retrieval.search(subject, item.domainCode(), item.question(), TOP_N, null);
                List<String> docNos = found.evidences().stream().map(Evidence::docNo).toList();
                return EvalMetrics.judge(item, mode, docNos, found.insufficient(), List.of(), null,
                        found.diagnostics().bestScore(), null, elapsed(startedAt));
            }
            GeneratedAnswer answer = generation.answer(subject, item.domainCode(), item.question(), TOP_N);
            List<String> docNos = answer.evidences().stream().map(Evidence::docNo).toList();
            List<String> cited = answer.citations().stream().map(GeneratedAnswer.Citation::docNo).toList();
            double best = docNos.isEmpty() ? 0d : answer.evidences().stream()
                    .mapToDouble(Evidence::bestScore).max().orElse(0d);
            return EvalMetrics.judge(item, mode, docNos, answer.refused(), cited, answer.answer(), best,
                    answer.traceId(), elapsed(startedAt));
        } catch (RuntimeException | IOException | InterruptedException ex) {
            return denied(item, mode, ex, elapsed(startedAt));
        }
    }

    /**
     * 被拒的处理：权限题的期望**就是**被挡（域级门直接抛），所以那是通过；其它情况是真异常，算失败。
     *
     * <p>这条区分很关键——不区分的话，模型出口挂掉会让权限题"看起来全对"，而真正的故障那一栏
     * 也永远不会有数字。</p>
     */
    private static CaseResult denied(Case item, String mode, Exception ex, long elapsedMs) {
        boolean expected = item.permission();
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return new CaseResult(item.caseId(), item.domainCode(), item.asker(), item.question(), item.reasoning(),
                item.expectedOutcome(), expected ? "denied" : "error", expected,
                expected ? null : "执行异常：" + reason,
                false, null, null, false, 0, null, String.join(",", item.allowedDocNos()), "", elapsedMs);
    }

    private List<Case> loadCases(String setName) throws IOException {
        Path file = questionFile(setName);
        JsonNode root = HttpSupport.MAPPER.readTree(Files.readString(file));
        JsonNode questions = root.path("questions");
        if (!questions.isArray()) {
            throw new IllegalArgumentException("题集格式不对（缺 questions 数组）：" + setName);
        }
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : questions) {
            JsonNode expect = node.path("expect");
            cases.add(new Case(
                    node.path("id").asText(),
                    node.path("domain").asText(),
                    node.path("asker").asText(),
                    node.path("question").asText(),
                    expect.path("reasoning").asText(null),
                    expect.path("outcome").asText("answered"),
                    expect.path("permission").asBoolean(false),
                    texts(expect.path("allowedDocNos")),
                    texts(expect.path("mention")),
                    texts(expect.path("forbiddenDocNos")),
                    texts(expect.path("noLeak"))));
        }
        return cases;
    }

    /** 文件名白名单：只认目录内已存在的 .json，把 {@code ../../} 这类输入挡在 Files 之外。 */
    private Path questionFile(String setName) {
        if (setName == null || setName.isBlank()) {
            throw new IllegalArgumentException("请指定题集");
        }
        String name = Path.of(setName).getFileName().toString();
        if (!questionSets().contains(name)) {
            throw new IllegalArgumentException("题集不存在：" + name + "（可选：" + questionSets() + "）");
        }
        return questionDir().resolve(name);
    }

    private String normalizeMode(String mode) {
        String value = mode == null || mode.isBlank() ? defaultMode() : mode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of(MODE_RETRIEVAL, MODE_FULL).contains(value)) {
            throw new IllegalArgumentException("模式只能是 retrieval 或 full，收到：" + value);
        }
        return value;
    }

    private String defaultMode() {
        Map<String, String> cfg = settings.effective(SettingService.GROUP_EVAL);
        String value = cfg.get("defaultMode");
        return value == null || value.isBlank() ? MODE_RETRIEVAL : value.trim().toLowerCase(Locale.ROOT);
    }

    private Path questionDir() {
        Map<String, String> cfg = settings.effective(SettingService.GROUP_EVAL);
        String configured = cfg.get("questionDir");
        String value = configured == null || configured.isBlank() ? "tools/questions" : configured.trim();
        Path path = Path.of(value);
        // 与 AssetStore 同一条约定：相对路径按进程工作目录（项目根）解析
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path))
                .normalize().toAbsolutePath();
    }

    private static List<String> texts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText();
            if (text != null && !text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static BigDecimal add(BigDecimal base, BigDecimal extra) {
        if (extra == null) {
            return base;
        }
        return base == null ? extra : base.add(extra);
    }
}
