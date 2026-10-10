package com.hykj.assistant.observe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hykj.assistant.generation.GeneratedAnswer;
import com.hykj.assistant.observe.ObservationModels.PriceSnapshot;
import com.hykj.assistant.observe.ObservationModels.Summary;
import com.hykj.assistant.observe.ObservationModels.TraceDetail;
import com.hykj.assistant.observe.ObservationModels.TraceInput;
import com.hykj.assistant.observe.ObservationModels.TraceRow;
import com.hykj.assistant.retrieval.RetrievalResult;
import com.hykj.assistant.security.AccessSubject;
import com.hykj.assistant.settings.SettingService;

/**
 * 运行记录（T10 / AC-P1-011）。
 *
 * <p>两件事刻意分开：{@code record} 只负责把已经算好的账写下来（不重新计算，
 * 否则账本和日志会给出两个版本的数字）；查询接口只读不写，观测本身不该产生副作用。</p>
 */
@Service
public class ObserveService {

    private static final Logger log = LoggerFactory.getLogger(ObserveService.class);

    /** 单价按"元/千 token"填，所以换算要除 1000；保留 6 位是为了小额也能看出差别。 */
    private static final BigDecimal TOKENS_PER_UNIT = BigDecimal.valueOf(1000);
    private static final int COST_SCALE = 6;

    private final QaTraceRepository repository;
    private final SettingService settingService;

    public ObserveService(QaTraceRepository repository, SettingService settingService) {
        this.repository = repository;
        this.settingService = settingService;
    }

    /**
     * 写一条问答账本。
     *
     * <p>同步写，不异步：一次问答本来就是秒级，多几毫秒不值得换来"异步队列丢了账"这种查不清的问题。
     * 事务范围只覆盖两条 insert，调用方那边会兜住异常——
     * **账本写失败绝不能让已经算出来的答案失败**。</p>
     */
    @Transactional
    public Optional<Long> record(AccessSubject subject, String domainCode, String question,
                                 RetrievalResult retrieval, GeneratedAnswer answer) {
        PriceSnapshot price = currentPrice();
        TraceInput input = new TraceInput(subject.username(), domainCode, question,
                retrieval, answer, price);
        BigDecimal cost = estimateCost(answer, price);
        long generationElapsedMs = Math.max(0,
                answer.diagnostics().elapsedMs() - retrieval.diagnostics().elapsedMs());
        long traceId = repository.insert(input, joinOutOfRange(answer), generationElapsedMs, cost);
        repository.insertFragments(traceId, answer.evidences(), citedChunkIds(answer));
        return Optional.of(traceId);
    }

    public List<TraceRow> recent(int limit, String domainCode, Boolean refused) {
        return repository.recent(Math.min(Math.max(limit, 1), 500), domainCode, refused);
    }

    public Optional<TraceDetail> detail(long id) {
        return repository.find(id);
    }

    public Summary summary(String domainCode, Boolean refused) {
        return repository.summary(domainCode, refused);
    }

    /**
     * 读当前单价。解析不出来就当成"没配"——单价是运营填的自由文本，
     * 为一个填错的数字让整次问答失败，代价完全不对等。
     */
    public PriceSnapshot currentPrice() {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_LLM);
        BigDecimal prompt = parsePrice(cfg.get("promptPricePer1k"));
        BigDecimal completion = parsePrice(cfg.get("completionPricePer1k"));
        if (prompt == null || completion == null) {
            return PriceSnapshot.UNPRICED;
        }
        return new PriceSnapshot(prompt, completion, "单价快照（元/千 token），改价不影响历史账本");
    }

    /** 包内可见是为了单测：单价是自由文本，"填错了"必须退化成没配，而不是把问答带崩。 */
    static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            return value.signum() < 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 包内可见是为了单测：这里唯一容易错的是单位（元/千 token），错了会静默给出小 1000 倍的金额。 */
    static BigDecimal estimateCost(GeneratedAnswer answer, PriceSnapshot price) {
        if (!price.priced()) {
            return null;
        }
        var diagnostics = answer.diagnostics();
        BigDecimal prompt = price.promptPer1k()
                .multiply(BigDecimal.valueOf(diagnostics.promptTokens()))
                .divide(TOKENS_PER_UNIT, COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal completion = price.completionPer1k()
                .multiply(BigDecimal.valueOf(diagnostics.completionTokens()))
                .divide(TOKENS_PER_UNIT, COST_SCALE, RoundingMode.HALF_UP);
        return prompt.add(completion);
    }

    /** 越界引用只在出错时有值，所以空的时候存 null 而不是空串——"没有"和"没记录"要能分开。 */
    private static String joinOutOfRange(GeneratedAnswer answer) {
        List<Integer> ranks = answer.diagnostics().outOfRangeCitations();
        if (ranks == null || ranks.isEmpty()) {
            return null;
        }
        return ranks.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<String> citedChunkIds(GeneratedAnswer answer) {
        if (answer.citations() == null) {
            return Set.of();
        }
        return answer.citations().stream()
                .map(GeneratedAnswer.Citation::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
    }
}
