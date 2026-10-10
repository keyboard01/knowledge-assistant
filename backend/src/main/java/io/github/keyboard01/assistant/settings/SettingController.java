package io.github.keyboard01.assistant.settings;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.keyboard01.assistant.settings.SettingModels.GroupView;
import io.github.keyboard01.assistant.settings.SettingModels.SaveRequest;
import io.github.keyboard01.assistant.settings.SettingModels.TestResult;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("groups", service.list());
    }

    @PutMapping("/{code}")
    public GroupView save(@PathVariable String code,
                          @RequestBody SaveRequest request,
                          HttpServletRequest httpRequest) {
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        return service.save(code, values, clientIp(httpRequest));
    }

    @PostMapping("/{code}/test")
    public TestResult test(@PathVariable String code) {
        return service.test(code);
    }

    @DeleteMapping("/{code}/{field}")
    public GroupView clear(@PathVariable String code, @PathVariable String field) {
        return service.clear(code, field, "ui");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
