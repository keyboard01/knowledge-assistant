package io.github.keyboard01.assistant.adapters.embedding;

import java.io.IOException;
import java.util.List;

/**
 * 向量化能力的内部契约。业务侧只认识这个接口——"用哪家 embedding"是配置，不是代码。
 *
 * <p>自己调向量化、自己算余弦：这样"该不该作答"这条线才在同一个量纲上可比较，尺度由我们定。</p>
 */
public interface TextEmbedder {

    /** 批量向量化，返回顺序与入参一一对应。 */
    List<float[]> embed(List<String> texts) throws IOException, InterruptedException;

    /** 当前配置的模型名（写进 chunk 行，将来换模型时能看出哪些行是旧模型算的）。 */
    String model();

    /** 维度。DDL 里写死 1024，配置改了维度就必须走迁移——这里用来提前报错而不是写坏库。 */
    int dimensions();
}