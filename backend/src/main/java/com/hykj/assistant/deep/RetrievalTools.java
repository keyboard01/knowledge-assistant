package com.hykj.assistant.deep;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hykj.assistant.ingest.DocumentRepository;
import com.hykj.assistant.retrieval.Evidence;
import com.hykj.assistant.retrieval.RetrievalResult;
import com.hykj.assistant.retrieval.RetrievalService;
import com.hykj.assistant.security.AccessFilter;
import com.hykj.assistant.security.AccessSubject;
import com.hykj.assistant.security.AclRule;
import com.hykj.assistant.security.DocumentAclRepository;

/**
 * {@link DeepTools} 的落地：检索走既有检索链路，读取走本地片段表。
 *
 * <p>**每次请求新建一个实例**（不是 Spring 单例）：身份与知识域是构造参数，而不是方法参数。
 * 这样一来，"模型能不能指定看得见什么"这件事在类型上就被否掉了——它连一个能填身份的入口都没有，
 * 而"服务端在实现里补全身份"这条设计（§4.5）也就不会随着后续改代码而悄悄失守。</p>
 */
public class RetrievalTools implements DeepTools {

    /** 每个子问题的检索条数：与快速模式一致。补的是"次数"，不是"一次要更多"。 */
    static final int SEARCH_TOP_N = RetrievalService.DEFAULT_TOP_N;

    /**
     * 一次读取最多几段：够覆盖一张表的数据行，又不至于把后续提示词撑爆。
     *
     * <p>10 是让确定性补齐的"锚点前后各 4 段"（9 段）能整窗读回来，再多就只是把提示词撑大。</p>
     */
    static final int MAX_READ_FRAGMENTS = 10;

    private final AccessSubject subject;
    private final String domainCode;
    private final RetrievalService retrievalService;
    private final DocumentRepository documents;
    private final DocumentAclRepository aclRepository;
    private final DeepChunkRepository chunks;

    public RetrievalTools(AccessSubject subject, String domainCode, RetrievalService retrievalService,
                          DocumentRepository documents, DocumentAclRepository aclRepository,
                          DeepChunkRepository chunks) {
        this.subject = subject;
        this.domainCode = domainCode;
        this.retrievalService = retrievalService;
        this.documents = documents;
        this.aclRepository = aclRepository;
        this.chunks = chunks;
    }

    @Override
    public Outcome search(String question) throws IOException, InterruptedException {
        RetrievalResult result = retrievalService.search(subject, domainCode, question, SEARCH_TOP_N, null);
        return new Outcome(result.evidences(), result.diagnostics().droppedUnauthorized(), result.hint(),
                result.accessNotice());
    }

    @Override
    public Outcome readDocument(long documentId, Integer fromChunkIndex, int limit) {
        Optional<DocumentRepository.Row> visible = visible(documentId);
        if (visible.isEmpty()) {
            return Outcome.empty();
        }
        int start = fromChunkIndex == null ? 0 : Math.max(0, fromChunkIndex);
        int size = Math.min(Math.max(limit, 1), MAX_READ_FRAGMENTS);
        List<DeepChunkRepository.Chunk> rows = chunks.findFrom(documentId, start, size);
        if (rows.isEmpty()) {
            return Outcome.empty();
        }
        DocumentRepository.Row doc = visible.get();
        List<Evidence.Fragment> fragments = rows.stream().map(RetrievalTools::toFragment).toList();
        // rank 先给 0：编号由合并阶段统一分配（全局连续），读取阶段自己编一个号会和检索来的证据撞号。
        // score 给 0：这段不是"算出来的相关"，是"顺着读下去的上下文"，用分数假装它相关会污染排序。
        Evidence evidence = new Evidence(0, doc.id(), doc.docNo(), doc.title(), doc.version(), doc.docStatus(),
                doc.secrecy(), doc.ownerDept(), doc.kernelDocName(), 0d, fragments);
        return new Outcome(List.of(evidence), 0, null, null);
    }

    @Override
    public boolean hasMore(long documentId, int afterChunkIndex) {
        return visible(documentId).isPresent() && chunks.hasMore(documentId, afterChunkIndex);
    }

    /**
     * 文档存在 + 当前身份可读。两个条件合成一个 Optional：外部只需要知道"能不能读"，
     * 分开返回等于把"这份文档存在但你没权限"这条信息漏出去。
     */
    private Optional<DocumentRepository.Row> visible(long documentId) {
        Optional<DocumentRepository.Row> row = documents.findById(documentId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        AccessFilter filter = AccessFilter.of(subject, AccessFilter.DEFAULT_ALIAS);
        Map<Long, List<AclRule>> acl = aclRepository.findByDocumentIds(List.of(documentId));
        if (!filter.allows(acl.getOrDefault(documentId, List.of()))) {
            return Optional.empty();
        }
        return row;
    }

    private static Evidence.Fragment toFragment(DeepChunkRepository.Chunk chunk) {
        String text = chunk.content() == null ? "" : chunk.content().strip();
        return new Evidence.Fragment(String.valueOf(chunk.chunkId()), chunk.chunkIndex(), text,
                0d, 1d, text.length(), chunk.pageFrom(), chunk.pageTo(), chunk.sectionPath(), chunk.kind(),
                0d, 0, 0);
    }
}
