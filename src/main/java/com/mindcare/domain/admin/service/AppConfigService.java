package com.mindcare.domain.admin.service;

import com.mindcare.domain.admin.dto.AppConfigResponse;
import com.mindcare.domain.admin.dto.AppConfigResponse.Maintenance;
import com.mindcare.domain.admin.dto.CrisisContact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기능 플래그·점검 모드 (TRD 7-8, PUB-02). 매 요청 점검 여부를 보므로 메모리에 들고 있는다.
 * 어드민이 바꾸면 커밋 직후 비우고(ADM-07), DB를 손으로 고친 경우를 위해 30초마다 다시 읽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppConfigService {

    static final Duration TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    private volatile Cached cached;

    private volatile CachedContacts contacts;

    private record Cached(AppConfigResponse config, Instant loadedAt) {
    }

    private record CachedContacts(List<CrisisContact> items, Instant loadedAt) {
    }

    /** V4 시드와 같은 값. DB를 못 읽을 때 쓴다. */
    static final List<CrisisContact> DEFAULT_CRISIS_CONTACTS = List.of(
            new CrisisContact("자살예방 상담전화", "109", "24시간 자살 위기 상담"),
            new CrisisContact("정신건강 위기상담 전화", "1577-0199", "24시간 정신건강 위기 상담"),
            new CrisisContact("경찰", "112", "긴급 신고"),
            new CrisisContact("119", "119", "응급 구조"));

    /** 설정이 바뀌었다는 신호. AppSettingService가 저장할 때 발행한다. */
    public record Changed(String key) {
    }

    public AppConfigResponse current() {
        Cached snapshot = cached;
        Instant now = clock.instant();
        if (snapshot == null || snapshot.loadedAt().plus(TTL).isBefore(now)) {
            snapshot = new Cached(load(), now);
            cached = snapshot;
        }
        return snapshot.config();
    }

    public boolean isMaintenance() {
        return current().maintenance().on();
    }

    /** 꺼진 P1 기능의 API는 이걸로 확인해서 403 FEATURE_DISABLED (FR-ADM-05). */
    public boolean isEnabled(String flag) {
        return Boolean.TRUE.equals(current().featureFlags().get(flag));
    }

    /**
     * PUB-01 위기 연락처 (price_configs crisis_contacts). 점검 중에도, DB를 못 읽어도 빈 목록이 되지 않게
     * 마지막으로 읽은 값 → 기본 연락처 순으로 돌려준다 (TRD: 위기 연락처는 오프라인·점검 중에도 보인다).
     */
    public List<CrisisContact> crisisContacts() {
        CachedContacts snapshot = contacts;
        Instant now = clock.instant();
        if (snapshot != null && !snapshot.loadedAt().plus(TTL).isBefore(now)) {
            return snapshot.items();
        }
        try {
            List<CrisisContact> loaded = loadCrisisContacts();
            contacts = new CachedContacts(loaded, now);
            return loaded;
        } catch (RuntimeException e) {
            log.error("crisis_contacts 를 읽지 못해 {} 값으로 응답합니다", snapshot != null ? "마지막" : "기본", e);
            return snapshot != null ? snapshot.items() : DEFAULT_CRISIS_CONTACTS;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onChanged(Changed event) {
        cached = null;
        contacts = null;
    }

    private List<CrisisContact> loadCrisisContacts() {
        List<String> rows = jdbcTemplate.queryForList(
                "select value::text from price_configs where key = 'crisis_contacts'", String.class);
        if (rows.isEmpty()) {
            return DEFAULT_CRISIS_CONTACTS;
        }
        JsonNode node = jsonMapper.readTree(rows.getFirst());
        List<CrisisContact> items = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node.values()) {
                String name = text(item.get("name"));
                String number = text(item.get("number"));
                if (name != null && number != null) {
                    items.add(new CrisisContact(name, number, text(item.get("description"))));
                }
            }
        }
        return items.isEmpty() ? DEFAULT_CRISIS_CONTACTS : List.copyOf(items);
    }

    private AppConfigResponse load() {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select key, value::text as value from price_configs
                 where key in ('feature_flags', 'maintenance', 'min_client_version')
                """, rs -> {
            rows.put(rs.getString("key"), jsonMapper.readTree(rs.getString("value")));
        });

        Map<String, Boolean> flags = new LinkedHashMap<>();
        JsonNode flagNode = rows.get("feature_flags");
        for (String flag : SettingSchemas.FEATURE_FLAGS.stream().sorted().toList()) {
            JsonNode value = flagNode == null ? null : flagNode.get(flag);
            flags.put(flag, value != null && value.isBoolean() && value.booleanValue());
        }

        Maintenance maintenance = Maintenance.OFF;
        JsonNode m = rows.get("maintenance");
        if (m != null && m.isObject()) {
            maintenance = new Maintenance(
                    m.get("on") != null && m.get("on").isBoolean() && m.get("on").booleanValue(),
                    text(m.get("message")),
                    text(m.get("until")));
        }

        return new AppConfigResponse(flags, maintenance, text(rows.get("min_client_version")));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
