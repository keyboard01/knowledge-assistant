package com.hykj.assistant.retrieval;

import java.io.IOException;
import java.util.List;

import com.hykj.assistant.security.AccessFilter;

/**
 * 检索能力的内部契约。
 *
 * <p>业务代码只认识这个接口：P1 的实现是 AnythingLLM 原生检索，P2 的实现是自建混合召回
 * （本地向量 + 本地关键词 + RRF）。换实现不动调用方。</p>
 *
 * <p>P2 起契约多带两样东西：{@code domainCode} 与 {@code filter}。内核实现用不上它们
 * （内核按工作区召回，权限在业务侧过滤），自建实现用得上——它要在 SQL 里就把不可见的文档挡掉。
 * 让实现各取所需，比让业务侧分两套调用要清楚。</p>
 */
public interface KnowledgeRetriever {

    /**
     * @param workspaceSlug 知识域对应的内核工作区
     * @param domainCode    知识域编码（自建检索按它筛文档）
     * @param filter        当前身份的可见范围（自建检索在 SQL 层就用它过滤，不等召回后再丢）
     * @param question      用户原始问题（P1/P2 都不做查询改写）
     * @param topN          最多返回几条；由调用方按场景给，不由检索实现决定
     * @param minScore      相似度下限，低于它的不要；0 表示不过滤
     */
    record Query(String workspaceSlug, String domainCode, AccessFilter filter, String question, int topN,
                 double minScore) {

        /** P1 的调用方式：不指定知识域与可见范围。 */
        public Query(String workspaceSlug, String question, int topN, double minScore) {
            this(workspaceSlug, null, null, question, topN, minScore);
        }
    }

    /**
     * 一次召回的结果。
     *
     * @param hits            本次可以交给下游的候选（自建通道已经按权限过滤过）
     * @param blockedHitCount "匹配上了、但当前身份看不见"的条数。它只为那句话存在：
     *                        "另有 N 条更相关的命中你无权访问"。过滤发生在取数阶段之后，
     *                        "没找到"和"找到了但你没权限"在候选里长得一模一样，
     *                        而对用户来说这两件事的下一步完全相反——一个该换关键词，一个该找管理员。
     */
    record Outcome(List<RetrievedPassage> hits, int blockedHitCount) {

        public static Outcome of(List<RetrievedPassage> hits) {
            return new Outcome(hits, 0);
        }
    }

    Outcome retrieve(Query query) throws IOException, InterruptedException;

    /** 只要候选、不要权限线索的调用方（自检探针）用这个。 */
    default List<RetrievedPassage> search(Query query) throws IOException, InterruptedException {
        return retrieve(query).hits();
    }

    /**
     * 这条通道的人话名字，用来在自检/日志里说清"现在走的是哪条链路"。
     *
     * <p>放在接口上而不是让调用方 {@code instanceof} 具体类：自检要报告的只是"用的是哪种召回"，
     * 为此去认一个内核包里的实现类，等于把内核实现的名字写进了业务层（A1 收口的一部分）。</p>
     */
    default String displayName() {
        return getClass().getSimpleName();
    }
}
