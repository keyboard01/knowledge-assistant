package io.github.keyboard01.assistant.observe;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.keyboard01.assistant.observe.EvalModels.RunDetail;
import io.github.keyboard01.assistant.observe.EvalModels.RunRow;
import io.github.keyboard01.assistant.security.CurrentUser;
import io.github.keyboard01.assistant.security.RequireRole;

/**
 * 评测（P3）。限管理员：跑一次评测要花模型钱，且题集与指标属于运维面。
 *
 * <p>{@code POST /runs} 是同步的：{@code retrieval} 模式秒级返回，{@code full} 模式要几分钟
 * （30 题 × 一道问答）。跑之前要想清楚——它真花钱。</p>
 */
@RestController
@RequestMapping("/api/eval")
@RequireRole("admin")
public class EvalController {

    public record RunRequest(String set, String mode) {
    }

    private final EvalService service;

    public EvalController(EvalService service) {
        this.service = service;
    }

    @PostMapping("/runs")
    public RunDetail run(@RequestBody RunRequest request) throws IOException, InterruptedException {
        return service.run(request.set(), request.mode(), CurrentUser.require());
    }

    /** 趋势：同一题集的历史 run 按时间倒序，指标可直接对比。 */
    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String set) {
        List<RunRow> rows = service.recent(limit, set);
        return Map.of("runs", rows, "sets", service.questionSets());
    }

    @GetMapping("/runs/{id}")
    public RunDetail detail(@PathVariable long id) {
        return service.detail(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这次评测：" + id));
    }
}
