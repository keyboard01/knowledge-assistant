package io.github.keyboard01.assistant.retrieval;

import java.io.IOException;
import java.util.List;

/**
 * 查询改写的内部契约（P1）。
 *
 * <p>用户口语化提问（"停机了怎么办"）与文档正式表述（"故障上报流程"）之间没有桥；
 * 型号/编号/版本这类"必须原样命中"的词又怕被改写冲掉。这个接口负责在检索前把问题
 * 改写成几条**更接近文档说法**的变体，与原问题一起召回，再进 RRF 融合。</p>
 *
 * <p>失败必须由调用方 fail-open：改写是"锦上添花"，拿不到变体就只用原问题，绝不让一次模型
 * 故障把检索整条链路带下去。和 {@link io.github.keyboard01.assistant.generation.LanguageModel} 一样，
 * 这是防腐层的接口一侧——换改写策略只换实现类。</p>
 */
public interface QueryRewriter {

    /** 是否启用改写。关闭时调用方只用原问题，行为与引入改写前逐位一致。 */
    boolean enabled();

    /**
     * 生成若干更适合检索的变体，**不含原问题**（调用方会自己把原问题放回最前）。
     * 拿不到有效变体时返回空表；调用方在异常时 fail-open。
     */
    List<String> rewrite(String question) throws IOException, InterruptedException;

    default String displayName() {
        return getClass().getSimpleName();
    }
}
