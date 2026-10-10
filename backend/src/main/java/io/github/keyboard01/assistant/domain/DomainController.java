package io.github.keyboard01.assistant.domain;

import java.util.List;
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
    public record DomainRequest(String code, String name, String description, String workspaceSlug, Boolean enabled) {
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

    /** 幂等地把全部知识域对应的工作区创建到 AnythingLLM。 */
    @PostMapping("/provision")
    @RequireRole("admin")
    public Map<String, Object> provision() {
        List<DomainService.ProvisionResult> results = service.provision();
        boolean allOk = results.stream().noneMatch(r -> "FAILED".equals(r.action()));
        return Map.of("ok", allOk, "results", results);
    }

    /** 新建知识域（admin）。落库后尽力初始化其内核工作区。 */
    @PostMapping
    @RequireRole("admin")
    public DomainService.DomainView create(@RequestBody DomainRequest request) {
        return service.create(request.code(), request.name(), request.workspaceSlug(), request.description(),
                request.enabled());
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

    /** 单个域的工作区初始化（admin，幂等）。 */
    @PostMapping("/{code}/provision")
    @RequireRole("admin")
    public DomainService.ProvisionResult provisionOne(@PathVariable String code) {
        return service.provisionOne(code);
    }

    /** 内核自检；启动时也会自动跑一次并写日志。 */
    @GetMapping("/selfcheck")
    public DomainService.SelfCheckResult selfCheck() {
        return service.selfCheck();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
}
