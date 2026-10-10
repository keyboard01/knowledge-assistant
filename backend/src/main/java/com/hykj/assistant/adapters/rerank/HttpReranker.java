package com.hykj.assistant.adapters.rerank;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.retrieval.Reranker;
import com.hykj.assistant.settings.SettingService;

/**
 * {@link Reranker} 的 HTTP 实现：运行期读「重排」配置分组。
 *
 * <p><b>默认关闭</b>：{@code enabled} 未显式置真时 {@link #enabled()} 返回 false，检索链路与重排引入前
 * 逐位一致。运维在系统配置里开启并填好端点后才生效——避免"未标定就上线"。</p>
 *
 * <p><b>熔断</b>：一次失败（网络/超时/解析错）就短路 {@link #COOLDOWN_NANOS}（30s）。深度通道一次会话
 * 会发起约 10 次检索，没有熔断的话一个挂掉的重排服务会在这 10 次里各等一次 3s 超时，把
 * {@code DeepBudget} 的耗时预算一点点吃光。熔断期间 {@link #enabled()} 为 false，调用方直接跳过。</p>
 */
@Component
public class HttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    /** 熔断冷却时长。 */
    static final long COOLDOWN_NANOS = Duration.ofSeconds(30).toNanos();

    private final SettingService settingService;
    private final RerankClient client;

    /** 熔断到期时刻（单调时钟）。0 表示未熔断。 */
    private volatile long disabledUntilNanos;

    public HttpReranker(SettingService settingService, RerankClient client) {
        this.settingService = settingService;
        this.client = client;
    }

    @Override
    public boolean enabled() {
        if (System.nanoTime() < disabledUntilNanos) {
            return false;
        }
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_RERANK);
        return isTrue(cfg.get("enabled"))
                && !HttpSupport.normalizeBase(cfg.get("baseUrl")).isEmpty()
                && !HttpSupport.text(cfg.get("model")).isEmpty();
    }

    @Override
    public List<Double> score(String query, List<String> documents) throws IOException, InterruptedException {
        try {
            List<Double> scores = client.score(settingService.effective(SettingService.GROUP_RERANK), query, documents);
            disabledUntilNanos = 0L;
            return scores;
        } catch (IOException | RuntimeException ex) {
            disabledUntilNanos = System.nanoTime() + COOLDOWN_NANOS;
            log.warn("重排调用失败，{}s 内跳过重排（fail-open 退回 RRF 原序）：{}",
                    COOLDOWN_NANOS / 1_000_000_000L, ex.toString());
            throw ex;
        }
    }

    @Override
    public String displayName() {
        return "HTTP 重排";
    }

    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("1")
                || normalized.equals("yes") || normalized.equals("on");
    }
}
