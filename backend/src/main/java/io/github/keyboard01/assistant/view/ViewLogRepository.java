package io.github.keyboard01.assistant.view;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.github.keyboard01.assistant.view.DocumentViewModels.Channel;

/**
 * 查看审计（T19）。
 *
 * <p><b>拒绝也要写一行。</b>只记成功的话，"有人试图打开秘密文档"这件事在系统里就没有痕迹，
 * 而它恰恰是最该被看见的。所以 {@code outcome} 有 OK / DENIED 两种。</p>
 *
 * <p><b>写审计失败不能让查看失败。</b>理由与运行账本一致（§10.10）：内容已经取到了，
 * 因为记账出问题就把它吞掉是本末倒置。这里吞异常只记 error 日志。</p>
 */
@Repository
public class ViewLogRepository {

    private final JdbcTemplate jdbc;

    public ViewLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long documentId, String docNo, String version, String username, String roleCode,
                       Channel channel, String outcome, String watermark, String clientIp) {
        try {
            jdbc.update("""
                    insert into document_view_log (document_id, doc_no, version, username, role_code,
                                                   channel, outcome, watermark, client_ip)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, documentId, docNo, version, username, roleCode, channel.name(), outcome, watermark,
                    clientIp);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(ViewLogRepository.class)
                    .error("写入查看审计失败（不影响本次查看）：user={} doc={} channel={}",
                            username, docNo, channel, ex);
        }
    }
}
