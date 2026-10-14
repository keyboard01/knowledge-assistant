package io.github.keyboard01.assistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.github.keyboard01.assistant.tenant.TenantRepository;

/**
 * 本地账号初始化：口令从环境变量（deploy\.env）读，不进 SQL 脚本、不进日志、不进代码。
 * 只补不覆盖——账号一旦存在就什么都不做，所以可以在任何环境反复启动。
 */
@Component
public class UserBootstrapRunner implements ApplicationRunner {

    private static final String ADMIN_PASSWORD_ENV = "ASSISTANT_ADMIN_PASSWORD";
    private static final String DEMO_PASSWORD_ENV = "ASSISTANT_DEMO_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapRunner.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final TenantRepository tenants;

    public UserBootstrapRunner(UserRepository users, PasswordHasher passwordHasher, TenantRepository tenants) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed("admin", "系统管理员", "admin", ADMIN_PASSWORD_ENV);
            // 演示账号用于验证"没有授权就看不到文档"：viewer 角色看不到秘密级文档
            seed("demo", "只读演示账号", "viewer", DEMO_PASSWORD_ENV);
            if (users.findAll().isEmpty()) {
                log.warn("库中还没有任何本地账号，无法登录：请在 deploy\\.env 里设置 {} 后重启", ADMIN_PASSWORD_ENV);
            }
        } catch (Exception ex) {
            log.warn("本地账号初始化跳过：{}", ex.getMessage());
        }
    }

    private void seed(String username, String displayName, String roleCode, String passwordEnv) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        String password = System.getenv(passwordEnv);
        if (password == null || password.isBlank()) {
            log.debug("未设置 {}，跳过账号 {}", passwordEnv, username);
            return;
        }
        users.insert(username, displayName, roleCode, null, passwordHasher.hash(password));
        // 全新库的账号也要有租户归属：V3 迁移只回填"迁移时就存在"的账号，
        // 而这两个种子账号是在迁移之后才建的——不给归属的话，登录成功却看不到任何知识域。
        users.findByUsername(username).ifPresent(created -> tenants.ensureMembership(created.id(),
                TenantRepository.DEFAULT_TENANT, "admin".equalsIgnoreCase(roleCode) ? "admin" : "member"));
        log.info("已初始化本地账号 {}（角色 {}，密码取自 {}，不落日志）", username, roleCode, passwordEnv);
    }
}
