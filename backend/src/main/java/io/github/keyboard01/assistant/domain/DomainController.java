package io.github.keyboard01.assistant.domain;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.security.RequireRole;

@RestController
@RequestMapping("/api/domains")
public class DomainController {

    /** 新建/更新的载荷。code 只在新建时用；改 code 等于换一个域，不做。 */
    public record DomainRequest(String code, String name, String description, Boolean enabled) {
    }

    private final DomainService service;

    public DomainController(DomainService service) {
        this.service = service;
    }

    /**
     * 知识域列表。每个域带上**当前身份可见**的文档数（AC-P1-009）：
     * 前端据此把"看不到任何文档"的域标出来，而不是让用户问一次被拒才知道。
     */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("domains", service.list(CurrentUser.subject()));
    }

    /** 新建知识域（admin）。 */
    @PostMapping
    @RequireRole("admin")
    public DomainService.DomainView create(@RequestBody DomainRequest request) {
        return service.create(request.code(), request.name(), request.description(), request.enabled());
    }

    /** 更新知识域的名称/描述/启用状态（admin）。 */
    @PutMapping("/{code}")
    @RequireRole("admin")
    public DomainService.DomainView update(@PathVariable String code, @RequestBody DomainRequest request) {
        return service.update(code, request.name(), request.description(), request.enabled());
    }

    /** 删除知识域（admin）。有文档时拒绝——先清理或改为停用。 */
    @DeleteMapping("/{code}")
    @RequireRole("admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        service.delete(code);
    }

    /** 本地自检（admin）：文档目录、图片资产目录、本地检索链路、向量化配置。 */
    @GetMapping("/selfcheck")
    @RequireRole("admin")
    public DomainService.SelfCheckResult selfCheck() {
        return service.selfCheck();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
