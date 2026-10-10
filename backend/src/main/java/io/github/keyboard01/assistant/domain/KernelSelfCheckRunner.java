package io.github.keyboard01.assistant.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检（T02）：把“内核可达 / 密钥可用 / 工作区齐备 / 两处 embedding 一致”打成人能看懂的一行行日志。
 * 绝不阻断启动——配置要进界面才改得了，服务起不来就没人能改。
 */
@Component
public class KernelSelfCheckRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KernelSelfCheckRunner.class);

    private final DomainService domainService;

    public KernelSelfCheckRunner(DomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void run(ApplicationArguments args) {
        DomainService.SelfCheckResult result;
        try {
            result = domainService.selfCheck();
        } catch (Exception ex) {
            log.warn("内核自检未能执行：{}", ex.getMessage());
            return;
        }

        log.info("================ 内核自检 ================");
        for (DomainService.CheckItem item : result.items()) {
            String line = "[" + item.status() + "] " + item.title() + " —— " + item.detail();
            switch (item.status()) {
                case "OK" -> log.info(line);
                case "WARN" -> log.warn(line);
                default -> log.error(line);
            }
        }
        if (result.vectorCount() != null) {
            log.info("内核向量总数：{}", result.vectorCount());
        }
        if (result.kernel() != null) {
            log.info("内核向量化：{} / {}；生成模型：{} / {}", result.kernel().embeddingEngine(),
                    result.kernel().embeddingModel(), result.kernel().llmProvider(), result.kernel().llmModel());
        }
        log.info("自检结论：{}（详情 GET /api/domains/selfcheck）", result.ok() ? "通过" : "未通过");
        log.info("=========================================");
    }
}
