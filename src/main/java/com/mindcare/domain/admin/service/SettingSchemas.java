package com.mindcare.domain.admin.service;

import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import tools.jackson.databind.JsonNode;

/**
 * ADM-07 키별 값 검증 (FR-ADM-04). 키 목록은 TRD 11장 price_configs 주석 + 7-8.
 * 틀린 곳은 모아서 422 VALIDATION_ERROR fields[{field: "value.price", reason}]로 돌려준다.
 * oncall·crisis_scripts·marketing_cost는 TRD에 형식이 아직 없어서 값이 있는지만 본다.
 */
final class SettingSchemas {

    static final Set<String> FEATURE_FLAGS = Set.of(
            "p1_certificates", "p1_supervision_requests", "p1_complaints", "p1_credential_auto_verify");

    private static final int MAX_PRICE = 1_000_000;

    private static final Map<String, BiConsumer<JsonNode, Errors>> SCHEMAS = new LinkedHashMap<>();

    static {
        BiConsumer<JsonNode, Errors> track = (v, e) -> e.object(v, "value", Set.of("price", "duration_min", "fee_rate"),
                () -> {
                    e.intRange(v.get("price"), "value.price", 0, MAX_PRICE);
                    e.intRange(v.get("duration_min"), "value.duration_min", 10, 180);
                    e.numberRange(v.get("fee_rate"), "value.fee_rate", 0, 1);
                });
        BiConsumer<JsonNode, Errors> priceOnly = (v, e) -> e.object(v, "value", Set.of("price"),
                () -> e.intRange(v.get("price"), "value.price", 0, MAX_PRICE));
        BiConsumer<JsonNode, Errors> anyValue = (v, e) -> { };

        SCHEMAS.put("coaching", track);
        SCHEMAS.put("professional", track);
        SCHEMAS.put("supervision", priceOnly);
        SCHEMAS.put("supervision_request", priceOnly);
        SCHEMAS.put("refund_policy", (v, e) -> e.object(v, "value", Set.of("full_refund_hours"),
                () -> e.intRange(v.get("full_refund_hours"), "value.full_refund_hours", 0, 168)));
        SCHEMAS.put("referral_respond_hours", (v, e) -> e.intRange(v, "value", 1, 168));
        SCHEMAS.put("referral_report", (v, e) -> e.object(v, "value",
                Set.of("lookback_days", "max_sessions", "max_supervised_trainees"), () -> {
                    e.intRange(v.get("lookback_days"), "value.lookback_days", 1, 365);
                    e.intRange(v.get("max_sessions"), "value.max_sessions", 1, 50);
                    e.intRange(v.get("max_supervised_trainees"), "value.max_supervised_trainees", 1, 20);
                }));
        SCHEMAS.put("crisis_contacts", SettingSchemas::crisisContacts);
        SCHEMAS.put("feature_flags", (v, e) -> e.object(v, "value", FEATURE_FLAGS, () -> {
            for (String flag : FEATURE_FLAGS) {
                e.bool(v.get(flag), "value." + flag);
            }
        }));
        SCHEMAS.put("maintenance", (v, e) -> e.object(v, "value", Set.of("on", "message", "until"), () -> {
            e.bool(v.get("on"), "value.on");
            e.optionalString(v.get("message"), "value.message", 200);
            e.optionalInstant(v.get("until"), "value.until");
        }));
        SCHEMAS.put("min_client_version", (v, e) -> e.string(v, "value", 20));
        SCHEMAS.put("oncall", anyValue);
        SCHEMAS.put("crisis_scripts", anyValue);
        SCHEMAS.put("marketing_cost", anyValue);
    }

    private SettingSchemas() {
    }

    static boolean isKnown(String key) {
        return SCHEMAS.containsKey(key);
    }

    static void validate(String key, JsonNode value) {
        Errors errors = new Errors();
        if (value == null || value.isNull()) {
            errors.add("value", "required");
        } else {
            SCHEMAS.get(key).accept(value, errors);
        }
        if (!errors.fields.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, Map.of("fields", errors.fields));
        }
    }

    // 위기 연락처는 점검 중에도 보여야 하는 값이라 비우지 못하게 한다
    private static void crisisContacts(JsonNode v, Errors e) {
        if (!v.isArray()) {
            e.add("value", "type");
            return;
        }
        if (v.size() < 1 || v.size() > 20) {
            e.add("value", "size");
            return;
        }
        int i = 0;
        for (JsonNode item : v.values()) {
            String path = "value[" + i++ + "]";
            e.object(item, path, Set.of("name", "number", "description"), () -> {
                e.string(item.get("name"), path + ".name", 50);
                e.string(item.get("number"), path + ".number", 20);
                e.optionalString(item.get("description"), path + ".description", 200);
            });
        }
    }

    private static final class Errors {

        private final List<Map<String, Object>> fields = new ArrayList<>();

        void add(String field, String reason) {
            fields.add(Map.of("field", field, "reason", reason));
        }

        /** 객체이고 모르는 키가 없을 때만 안쪽 검사를 한다. */
        void object(JsonNode v, String path, Set<String> allowed, Runnable inner) {
            if (v == null || !v.isObject()) {
                add(path, v == null ? "required" : "type");
                return;
            }
            boolean unknown = false;
            for (String name : v.propertyNames()) {
                if (!allowed.contains(name)) {
                    add(path + "." + name, "unknown_key");
                    unknown = true;
                }
            }
            if (!unknown) {
                inner.run();
            }
        }

        void intRange(JsonNode v, String path, int min, int max) {
            if (v == null || v.isNull()) {
                add(path, "required");
            } else if (!v.isIntegralNumber() || !v.canConvertToInt()) {
                add(path, "type");
            } else if (v.asInt() < min || v.asInt() > max) {
                add(path, "range");
            }
        }

        void numberRange(JsonNode v, String path, double min, double max) {
            if (v == null || v.isNull()) {
                add(path, "required");
            } else if (!v.isNumber()) {
                add(path, "type");
            } else if (v.asDouble() < min || v.asDouble() > max) {
                add(path, "range");
            }
        }

        void bool(JsonNode v, String path) {
            if (v == null || v.isNull()) {
                add(path, "required");
            } else if (!v.isBoolean()) {
                add(path, "type");
            }
        }

        void string(JsonNode v, String path, int maxLength) {
            if (v == null || v.isNull()) {
                add(path, "required");
            } else if (!v.isString()) {
                add(path, "type");
            } else if (v.stringValue().isBlank()) {
                add(path, "required");
            } else if (v.stringValue().length() > maxLength) {
                add(path, "max_length");
            }
        }

        void optionalString(JsonNode v, String path, int maxLength) {
            if (v == null || v.isNull()) {
                return;
            }
            if (!v.isString()) {
                add(path, "type");
            } else if (v.stringValue().length() > maxLength) {
                add(path, "max_length");
            }
        }

        void optionalInstant(JsonNode v, String path) {
            if (v == null || v.isNull()) {
                return;
            }
            if (!v.isString()) {
                add(path, "type");
                return;
            }
            try {
                Instant.parse(v.stringValue());
            } catch (DateTimeParseException ex) {
                add(path, "format");
            }
        }
    }
}
