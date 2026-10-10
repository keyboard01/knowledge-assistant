package io.github.keyboard01.assistant.settings;

import java.util.List;
import java.util.Map;

public final class SettingModels {

    private SettingModels() {
    }

    public record FieldDef(String key, String label, boolean secret, String hint, String defaultValue) {
    }

    public record FieldView(String key, String label, boolean secret, boolean hasValue,
                            String value, String mask, String hint) {
    }

    public record GroupView(String code, String title, String description, List<FieldView> fields) {
    }

    public record SaveRequest(Map<String, String> values) {
    }

    public record TestResult(boolean ok, String message, String detail) {
    }
}
