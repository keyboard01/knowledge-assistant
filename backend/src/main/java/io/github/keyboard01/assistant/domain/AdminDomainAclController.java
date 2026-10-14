package io.github.keyboard01.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.security.AclRule;
import io.github.keyboard01.assistant.security.CurrentUser;

/**
 * 工作区(知识域)级授权的管理接口：谁能看哪个域。
 * 权限本身不走前端——前端提交的是"授权规则"，可见范围仍由服务端按规则判定。
 *
 * <p>多项目接入 S3 起不再用类级 `@RequireRole("admin")`：平台管理员与**该域的租户管理员**
 * 都能管，判定统一落在 {@code DomainService.canAdminister}（按域的租户判）。</p>
 */
@RestController
@RequestMapping("/api/admin/domains")
public class AdminDomainAclController {

    public record AclRuleDto(String subjectType, String subjectValue, String permission) {
    }

    public record AclRulesRequest(List<AclRuleDto> rules) {
    }

    private static final Set<String> SUBJECT_TYPES = Set.of("ALL", "ROLE", "DEPT", "USER");

    private final DomainService service;

    public AdminDomainAclController(DomainService service) {
        this.service = service;
    }

    @GetMapping("/{code}/acl")
    public Map<String, Object> list(@PathVariable String code) {
        return Map.of("rules", service.listAcl(CurrentUser.subject(), code).stream()
                .map(AdminDomainAclController::toDto).toList());
    }

    /** 整份替换：提交什么就是什么（空列表 = 只有管理员可见）。 */
    @PutMapping("/{code}/acl")
    public Map<String, Object> replace(@PathVariable String code, @RequestBody AclRulesRequest request) {
        List<AclRule> rules = new ArrayList<>();
        if (request.rules() != null) {
            for (AclRuleDto dto : request.rules()) {
                if (dto == null) {
                    continue;
                }
                String type = normalizeType(dto.subjectType());
                String value = dto.subjectValue() == null ? "" : dto.subjectValue().trim();
                if ("ALL".equals(type)) {
                    value = "*";
                } else if (value.isEmpty()) {
                    throw new IllegalArgumentException(type + " 类授权必须给出取值（角色码/部门码/用户名）");
                }
                rules.add(new AclRule(type, value, AclRule.READ));
            }
        }
        return Map.of("rules", service.replaceAcl(CurrentUser.subject(), code, rules).stream()
                .map(AdminDomainAclController::toDto).toList());
    }

    private static AclRuleDto toDto(AclRule rule) {
        return new AclRuleDto(rule.subjectType(), rule.subjectValue(), rule.permission());
    }

    private static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (!SUBJECT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("授权主体只能是 ALL / ROLE / DEPT / USER：" + type);
        }
        return normalized;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
