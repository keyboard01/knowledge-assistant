package com.hykj.assistant.adapters.layout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.parse.LayoutParser;
import com.hykj.assistant.parse.ParsedBlock;
import com.hykj.assistant.settings.SettingService;

/**
 * {@link LayoutParser} 的 HTTP 实现（P1 解析升级）：读「版面分析」配置分组。
 *
 * <p>默认关闭：{@code enabled} 未显式置真或未填地址时 {@link #enabled()} 返回 false，解析完全走内置
 * PDFBox/POI。开启后把文件交给外部版面分析服务；服务报错/超时/返回空一律返回 {@code empty()}，
 * 由 {@link com.hykj.assistant.parse.DocumentParseService} 回落内置解析器——解析入口不能因为可选服务挂掉就断。</p>
 */
@Component
public class HttpLayoutParser implements LayoutParser {

    private static final Logger log = LoggerFactory.getLogger(HttpLayoutParser.class);

    private final SettingService settingService;
    private final LayoutClient client;

    public HttpLayoutParser(SettingService settingService, LayoutClient client) {
        this.settingService = settingService;
        this.client = client;
    }

    @Override
    public boolean enabled() {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_LAYOUT);
        return isTrue(cfg.get("enabled")) && !HttpSupport.normalizeBase(cfg.get("baseUrl")).isEmpty();
    }

    @Override
    public Optional<List<ParsedBlock>> parse(Path file) throws IOException, InterruptedException {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_LAYOUT);
        try {
            List<ParsedBlock> blocks = client.parse(cfg, file);
            return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("版面分析失败，回落内置解析器（fail-open）：{} —— {}", file.getFileName(), ex.toString());
            return Optional.empty();
        }
    }

    @Override
    public String displayName() {
        return "版面分析服务";
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
