package io.github.keyboard01.assistant.observe;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.keyboard01.assistant.observe.ObservationModels.PriceSnapshot;
import io.github.keyboard01.assistant.observe.ObservationModels.Summary;
import io.github.keyboard01.assistant.observe.ObservationModels.TraceDetail;
import io.github.keyboard01.assistant.observe.ObservationModels.TraceRow;
import io.github.keyboard01.assistant.security.RequireRole;

/**
 * 运行记录查询（T10）。
 *
 * <p>整个控制器都限管理员：账本里存着**全部人**的提问原话与命中情况，
 * 它天然比被提问的知识库更敏感——一个 viewer 能翻别人的提问，就等于绕过了文档级 ACL 的意图
 * （"谁在问薪酬、谁在问辞退"本身就泄露了信息）。</p>
 */
@RestController
@RequestMapping("/api/observe")
@RequireRole("admin")
public class ObserveController {

    private final ObserveService service;

    public ObserveController(ObserveService service) {
        this.service = service;
    }

    /** {@code refused} 用三态："未传"=全部、"true"=只看拒答、"false"=只看放行。 */
    @GetMapping("/traces")
    public Map<String, Object> traces(@RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String domainCode,
                                      @RequestParam(required = false) Boolean refused) {
        List<TraceRow> rows = service.recent(limit, domainCode, refused);
        return Map.of("traces", rows, "summary", service.summary(domainCode, refused));
    }

    @GetMapping("/traces/{id}")
    public TraceDetail detail(@PathVariable long id) {
        return service.detail(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这条运行记录：" + id));
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(required = false) String domainCode,
                           @RequestParam(required = false) Boolean refused) {
        return service.summary(domainCode, refused);
    }

    /** 当前生效的单价快照，供界面说明"费用是按什么价算的"。 */
    @GetMapping("/price")
    public PriceSnapshot price() {
        return service.currentPrice();
    }
}
