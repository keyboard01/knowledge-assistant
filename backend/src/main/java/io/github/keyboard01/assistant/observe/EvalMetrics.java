package io.github.keyboard01.assistant.observe;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.keyboard01.assistant.observe.EvalModels.Case;
import io.github.keyboard01.assistant.observe.EvalModels.CaseResult;
import io.github.keyboard01.assistant.observe.EvalModels.Metrics;

/**
 * 评测判定与指标计算。**纯函数**，与检索/模型无关，可直接单测。
 *
 * <p>判定口径刻意与 `tools/verify-questions.ps1` 保持一致（同一批题、同一套断言）：
 * 两处不一致的话，"脚本通过但评测不通过"会变成日常，最后谁也不信任何一个。</p>
 *
 * <p>{@code retrieval} 模式只判"检索有没有拿到该拿的文档"，不判答案文字——
 * 没有调模型就没有答案可判。它的价值在于：秒级、不花钱，适合每次改动后随手跑一遍。</p>
 */
public final class EvalMetrics {

    /** 权限题话术：与脚本同一条判据，只钉"理由是权限"，不钉具体句式。 */
    private static final String PERMISSION_WORDING = "权访问|访问权限";

    private EvalMetrics() {
    }

    public static CaseResult judge(Case item, String mode, List<String> evidenceDocNos, boolean refused,
                                   List<String> citedDocNos, String answerText, Double bestScore,
                                   Long traceId, long elapsedMs) {
        boolean full = "full".equals(mode);
        List<String> failures = new ArrayList<>();
        boolean leaked = false;

        // 1) 泄露：被挡文档出现在证据或引用里 = 权限下推失效。这条与"答得好不好"无关，一律算硬失败
        for (String docNo : concat(evidenceDocNos, citedDocNos)) {
            if (item.forbiddenDocNos().contains(docNo)) {
                leaked = true;
                failures.add("引用了不该引用的文档 " + docNo);
            }
        }
        // 2) 只存在于被挡文档里的词露出来了（只有 full 模式有正文可比）
        if (full && answerText != null) {
            String flat = squash(answerText);
            for (String word : item.noLeak()) {
                if (flat.contains(squash(word))) {
                    leaked = true;
                    failures.add("答案里出现了被挡文档才有的内容：" + word);
                }
            }
        }
        // 3) 期望文档有没有进证据、排第几
        int rank = 0;
        for (int i = 0; i < evidenceDocNos.size(); i++) {
            if (item.allowedDocNos().contains(evidenceDocNos.get(i))) {
                rank = i + 1;
                break;
            }
        }

        // 4) 期望结果
        String actual;
        Boolean citationOk = null;
        if (!full) {
            actual = evidenceDocNos.isEmpty() ? "missed" : "retrieved";
            if (!item.allowedDocNos().isEmpty() && rank == 0) {
                failures.add("期望文档没进证据");
            }
        } else {
            actual = refused ? "refused" : "answered";
            if (!refused) {
                boolean cited = !citedDocNos.isEmpty();
                boolean intersects = item.allowedDocNos().isEmpty()
                        || citedDocNos.stream().anyMatch(item.allowedDocNos()::contains);
                citationOk = cited && intersects;
            }
            if ("answered".equals(item.expectedOutcome())) {
                if (refused) {
                    failures.add("本该作答却拒答");
                } else {
                    if (citedDocNos.isEmpty()) {
                        failures.add("没有引用");
                    } else if (!item.allowedDocNos().isEmpty()
                            && citedDocNos.stream().noneMatch(item.allowedDocNos()::contains)) {
                        failures.add("没引用到期望文档");
                    }
                    String flat = squash(answerText == null ? "" : answerText);
                    for (String word : item.mention()) {
                        if (!flat.contains(squash(word))) {
                            failures.add("答案里没有关键事实：" + word);
                        }
                    }
                }
            } else if (refused != "refused".equals(item.expectedOutcome())) {
                failures.add("本该拒答却作答");
            }
            if (item.permission() && !squash(answerText == null ? "" : answerText).matches(".*(" + PERMISSION_WORDING + ").*")) {
                failures.add("权限题没有说明是权限原因");
            }
        }

        String expectedDocNos = String.join(",", item.allowedDocNos());
        String cited = String.join(",", citedDocNos);
        return new CaseResult(item.caseId(), item.domainCode(), item.asker(), item.question(), item.reasoning(),
                item.expectedOutcome(), actual, failures.isEmpty(),
                failures.isEmpty() ? null : String.join("；", failures),
                leaked, citationOk, traceId, rank > 0, rank, bestScore, expectedDocNos, cited, elapsedMs);
    }

    /** 汇总。每项指标只管它能管的那部分样本，分母都写清楚（见各字段注释）。 */
    public static Metrics summarize(List<CaseResult> cases, long promptTokens, long completionTokens,
                                    BigDecimal costTotal) {
        int passed = 0;
        int failed = 0;
        int leaked = 0;
        int withExpectation = 0;
        int hit = 0;
        double reciprocal = 0d;
        double scoreSum = 0d;
        int scoreCount = 0;
        long elapsedSum = 0;
        int citeChecked = 0;
        int citeOk = 0;
        int refusalChecked = 0;
        int refusalOk = 0;
        for (CaseResult item : cases) {
            if (item.ok()) {
                passed++;
            } else {
                failed++;
            }
            if (item.leaked()) {
                leaked++;
            }
            elapsedSum += item.elapsedMs();
            if (item.bestScore() != null) {
                scoreSum += item.bestScore();
                scoreCount++;
            }
            if (item.expectedDocNos() != null && !item.expectedDocNos().isBlank()) {
                withExpectation++;
                if (item.hit()) {
                    hit++;
                    reciprocal += 1d / item.docRank();
                }
            }
            if (item.citationOk() != null) {
                citeChecked++;
                if (item.citationOk()) {
                    citeOk++;
                }
            }
            if ("answered".equals(item.expectedOutcome()) || "refused".equals(item.expectedOutcome())) {
                if (!"retrieved".equals(item.actualOutcome()) && !"missed".equals(item.actualOutcome())) {
                    refusalChecked++;
                    if (item.actualOutcome().equals(item.expectedOutcome())) {
                        refusalOk++;
                    }
                }
            }
        }
        return new Metrics(
                withExpectation == 0 ? 0d : round(hit * 1d / withExpectation),
                withExpectation == 0 ? 0d : round(reciprocal / withExpectation),
                citeChecked == 0 ? null : round(citeOk * 1d / citeChecked),
                refusalChecked == 0 ? null : round(refusalOk * 1d / refusalChecked),
                leaked,
                scoreCount == 0 ? null : round(scoreSum / scoreCount),
                cases.isEmpty() ? 0 : elapsedSum / cases.size(),
                passed, failed, promptTokens, completionTokens, costTotal);
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>(left.size() + right.size());
        merged.addAll(left);
        merged.addAll(right);
        return merged;
    }

    /** 比对前先去掉所有空白：模型输出里的换行、全角空格不该让"必含事实"判失败。 */
    private static String squash(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
