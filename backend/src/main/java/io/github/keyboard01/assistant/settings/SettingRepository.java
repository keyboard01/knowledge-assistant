package io.github.keyboard01.assistant.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingRepository {

    private final JdbcTemplate jdbc;

    public SettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> loadAll() {
        Map<String, String> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select group_code, field_key, field_value from app_setting");
        for (Map<String, Object> row : rows) {
            Object value = row.get("field_value");
            result.put(row.get("group_code") + "." + row.get("field_key"),
                    value == null ? "" : value.toString());
        }
        return result;
    }

    public void upsert(String group, String key, String value, boolean secret, String operator) {
        jdbc.update("""
                insert into app_setting (group_code, field_key, field_value, secret, updated_at, updated_by)
                values (?, ?, ?, ?, now(), ?)
                on conflict (group_code, field_key) do update
                   set field_value = excluded.field_value,
                       secret      = excluded.secret,
                       updated_at  = now(),
                       updated_by  = excluded.updated_by
                """, group, key, value, secret, operator);
    }

    public void delete(String group, String key) {
        jdbc.update("delete from app_setting where group_code = ? and field_key = ?", group, key);
    }
}
