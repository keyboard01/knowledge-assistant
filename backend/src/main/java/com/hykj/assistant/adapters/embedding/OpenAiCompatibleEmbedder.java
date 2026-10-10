package com.hykj.assistant.adapters.embedding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.hykj.assistant.adapters.HttpSupport;
import com.hykj.assistant.settings.SettingService;

/**
 * OpenAI 兼容协议的向量化客户端（OpenAI 兼容服务 嵌入模型 走这个协议）。
 *
 * <p>批大小固定 10：该服务对单次 input 条数有限制，超了会直接报错。批量调用是必须的——
 * 一份文档几十个片段，一个一个调会把入库时间拖到不可接受。</p>
 */
@Component
public class OpenAiCompatibleEmbedder implements TextEmbedder {

    /** 该服务 compatible-mode 单次最多 10 条 input。 */
    private static final int BATCH_SIZE = 10;

    private final SettingService settingService;

    public OpenAiCompatibleEmbedder(SettingService settingService) {
        this.settingService = settingService;
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException, InterruptedException {
        if (texts.isEmpty()) {
            return List.of();
        }
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_EMBEDDING);
        String baseUrl = HttpSupport.normalizeBase(cfg.get("baseUrl"));
        String apiKey = HttpSupport.text(cfg.get("apiKey"));
        String model = HttpSupport.text(cfg.get("model"));
        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("向量化配置不完整：请在系统配置里填写 embedding 的地址、API Key 与模型名");
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = List.copyOf(texts.subList(start, Math.min(texts.size(), start + BATCH_SIZE)));
            JsonNode response = HttpSupport.postJson(baseUrl + "/embeddings", apiKey,
                    Map.of("model", model, "input", batch));
            JsonNode data = response.path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new IllegalStateException("向量化返回条数与请求不一致：" + data.size() + "/" + batch.size());
            }
            // 按返回里的 index 归位，不信它一定按顺序给
            float[][] ordered = new float[batch.size()][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt();
                JsonNode vector = item.path("embedding");
                if (index < 0 || index >= ordered.length || !vector.isArray()) {
                    throw new IllegalStateException("向量化返回内容无法解析：" + HttpSupport.brief(item));
                }
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                ordered[index] = values;
            }
            for (float[] vector : ordered) {
                if (vector == null) {
                    throw new IllegalStateException("向量化返回缺少某一条的结果");
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }

    @Override
    public String model() {
        return HttpSupport.text(settingService.effective(SettingService.GROUP_EMBEDDING).get("model"));
    }

    @Override
    public int dimensions() {
        String configured = HttpSupport.text(settingService.effective(SettingService.GROUP_EMBEDDING).get("dimension"));
        try {
            return configured.isEmpty() ? 0 : Integer.parseInt(configured);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}