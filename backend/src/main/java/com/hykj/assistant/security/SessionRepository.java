package com.hykj.assistant.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    public record Row(long userId, Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tokenHash, long userId, Instant expiresAt) {
        jdbc.update("insert into app_session (token_hash, user_id, expires_at) values (?, ?, ?)",
                tokenHash, userId, Timestamp.from(expiresAt));
    }

    /** 只认未过期的会话：过期即视为未登录，不等清理任务。 */
    public Optional<Row> findActive(String tokenHash) {
        return jdbc.query("""
                        select user_id, expires_at from app_session
                         where token_hash = ? and expires_at > now()
                        """,
                (rs, rowNum) -> new Row(rs.getLong(1), rs.getTimestamp(2).toInstant()), tokenHash)
                .stream().findFirst();
    }

    public void delete(String tokenHash) {
        jdbc.update("delete from app_session where token_hash = ?", tokenHash);
    }

    public int deleteExpired() {
        return jdbc.update("delete from app_session where expires_at <= now()");
    }

    /** 改密后作废该账号的其它会话，只保留当前这一个，免得自己把自己踢下线。 */
    public int deleteByUserExcept(long userId, String keepTokenHash) {
        return jdbc.update("delete from app_session where user_id = ? and token_hash <> ?", userId, keepTokenHash);
    }
}
