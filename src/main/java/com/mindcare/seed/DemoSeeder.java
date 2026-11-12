package com.mindcare.seed;

import com.mindcare.domain.admin.service.PriceConfigService;
import com.mindcare.domain.settlement.service.SettlementService;
import com.mindcare.global.auth.AuthAccounts;
import com.mindcare.global.crypto.SensitiveTextCodec;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * TRD 19-3 시연용 시드. 계정 9개(+ 이력용 내담자 1명)와 각 시연 장면에 필요한 데이터를 만든다.
 *
 * <ul>
 *   <li>Firebase 계정은 uid를 {@code demo-<키>}로 고정해서 만들고(있으면 비밀번호만 맞춤), DB는 한 트랜잭션으로 넣는다.</li>
 *   <li>다시 실행하면 {@code demo-} 계정과 그 데이터만 지우고 새로 넣는다. 실제 가입자 데이터는 건드리지 않는다.</li>
 *   <li>여러 도메인 테이블에 걸쳐 있어서 서비스 대신 SQL로 넣는다 (정산만 SettlementService 사용).
 *       스키마가 바뀌면 이 파일도 같이 고쳐야 한다.</li>
 *   <li>대화·리포트·슈퍼비전 문장은 팀이 쓴 합성 문장이다 (실제 사례 아님).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoSeeder {

    static final String UID_PREFIX = "demo-";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CONSENT_VERSION = "1.0";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SensitiveTextCodec codec;
    private final AuthAccounts authAccounts;
    private final SettlementService settlementService;
    private final PriceConfigService priceConfigService;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final EntityManager entityManager;

    @Value("${app.seed.password:}")
    private String password;

    @Value("${app.seed.email-domain:demo.mindcare.test}")
    private String emailDomain;

    /** 시드와 같이 지울 다른 계정 Firebase uid (운영 테스트 계정 정리용, 콤마 구분). */
    @Value("${app.seed.purge-uids:}")
    private String purgeUids;

    public record Result(int users, int sessions, int slots) {
    }

    record Account(String key, String nickname, String role, String status) {
    }

    static final List<Account> ACCOUNTS = List.of(
            new Account("client1", "지원", "client", "active"),
            new Account("client2", "민수", "client", "active"),
            new Account("client3", "하루", "client", "active"),
            new Account("trainee1", "하늘", "trainee", "active"),
            new Account("trainee2", "새벽", "trainee", "active"),
            new Account("trainee3", "노을", "trainee", "pending_review"),
            new Account("trainee4", "바다", "trainee", "active"),
            new Account("expert1", "윤선생님", "expert", "active"),
            new Account("expert2", "정선생님", "expert", "pending_review"),
            new Account("admin", "당직운영", "admin", "active"));

    public Result seed() {
        if (authAccounts.isReal() && (!StringUtils.hasText(password) || password.length() < 8)) {
            throw new IllegalStateException("SEED_PASSWORD(8자 이상)가 필요합니다");
        }
        Map<String, UUID> ids = new LinkedHashMap<>();
        Map<String, String> uids = new LinkedHashMap<>();
        for (Account account : ACCOUNTS) {
            String uid = authAccounts.ensure(UID_PREFIX + account.key(), account.key() + "@" + emailDomain, password,
                    account.nickname());
            uids.put(account.key(), uid);
        }
        return tx.execute(status -> {
            // 정산은 JPA로 들어가서, SQL로 지우거나 세기 전에 DB에 반영해 둔다
            entityManager.flush();
            entityManager.clear();
            purge();
            Run run = new Run(clock.instant(), ids);
            run.users(uids);
            run.profilesAndCredentials();
            run.history();
            int slots = run.upcomingSlots();
            entityManager.flush();
            log.info("seed accounts: {}", String.join(", ", uids.keySet()));
            return new Result(ids.size(), run.sessionCount, slots);
        });
    }

    // ---------------------------------------------------------------- 지우기

    /** demo- 계정(+ purge-uids)과 그 사람들이 얽힌 데이터를 FK 순서대로 지운다. */
    private void purge() {
        List<String> extra = Arrays.stream(purgeUids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        List<UUID> users = jdbc.queryForList(
                "select id from users where firebase_uid like 'demo-%' or firebase_uid = any(?)", UUID.class,
                (Object) extra.toArray(String[]::new));
        if (users.isEmpty()) {
            return;
        }
        UUID[] u = users.toArray(UUID[]::new);
        List<UUID> sessions = jdbc.queryForList(
                "select id from sessions where client_id = any(?) or provider_id = any(?)", UUID.class, u, u);
        UUID[] s = sessions.toArray(UUID[]::new);

        jdbc.update("""
                delete from strikes where trainee_id = any(?)
                    or complaint_id in (select id from complaints where session_id = any(?))""", u, s);
        jdbc.update("""
                delete from client_sanctions where client_id = any(?)
                    or complaint_id in (select id from complaints where session_id = any(?))""", u, s);
        jdbc.update("delete from complaints where session_id = any(?) or target_id = any(?) or reporter_id = any(?)",
                s, u, u);
        jdbc.update("""
                delete from settlements where provider_id = any(?) or session_id = any(?)
                    or supervision_id in (select id from supervisions where expert_id = any(?) or trainee_id = any(?))""",
                u, s, u, u);
        jdbc.update("delete from supervisions where expert_id = any(?) or trainee_id = any(?)", u, u);
        jdbc.update("delete from supervision_requests where trainee_id = any(?) or session_id = any(?)", u, s);
        jdbc.update("delete from certificates where trainee_id = any(?)", (Object) u);
        jdbc.update("update referrals set report_id = null, expert_session_id = null where client_id = any(?)",
                (Object) u);
        jdbc.update("delete from consents where user_id = any(?) or target_expert_id = any(?)", u, u);
        jdbc.update("""
                delete from report_sources where session_id = any(?)
                    or report_id in (select id from reports where client_id = any(?))""", s, u);
        jdbc.update("delete from reports where client_id = any(?)", (Object) u);
        jdbc.update("delete from referrals where client_id = any(?) or expert_id = any(?)", u, u);
        jdbc.update("delete from risk_events where client_id = any(?) or session_id = any(?)", u, s);
        jdbc.update("delete from payments where session_id = any(?) or client_id = any(?)", s, u);
        jdbc.update("delete from sessions where id = any(?)", (Object) s);
        jdbc.update("delete from availability_slots where provider_id = any(?)", (Object) u);
        jdbc.update("delete from matches where provider_id = any(?)", (Object) u);
        jdbc.update("delete from intakes where client_id = any(?)", (Object) u);
        jdbc.update("delete from analytics_events where user_id = any(?)", (Object) u);
        jdbc.update("update price_configs set updated_by = null where updated_by = any(?)", (Object) u);
        // credentials.reviewed_by가 지울 어드민을 가리킬 수 있다 (실제 가입자 자격은 남기고 심사자만 비움)
        jdbc.update("update credentials set reviewed_by = null where reviewed_by = any(?)", (Object) u);
        // notifications·device_tokens·credentials·provider_profiles·identity_verifications는 users 삭제 시 cascade
        jdbc.update("delete from users where id = any(?)", (Object) u);
        log.info("seed purge: users={}, sessions={}", users.size(), sessions.size());
    }

    // ---------------------------------------------------------------- 넣기

    /** 한 번 실행하는 동안의 상태 (기준 시각, 계정 id). */
    private final class Run {

        private final Instant now;
        private final Map<String, UUID> ids;
        private final int coachingPrice;
        private final int coachingMinutes;
        int sessionCount;

        Run(Instant now, Map<String, UUID> ids) {
            this.now = now;
            this.ids = ids;
            PriceConfigService.TrackPricing coaching = priceConfigService.trackPricing(PriceConfigService.COACHING);
            this.coachingPrice = coaching.price();
            this.coachingMinutes = coaching.durationMinutes();
        }

        UUID id(String key) {
            return ids.get(key);
        }

        void users(Map<String, String> uids) {
            Instant joined = daysAgo(120, 12);
            for (Account account : ACCOUNTS) {
                UUID userId = SeedIds.at(joined);
                ids.put(account.key(), userId);
                jdbc.update("""
                        insert into users (id, firebase_uid, role, nickname, status, training_data_opt_in, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""",
                        userId, uids.get(account.key()), account.role(), account.nickname(), account.status(),
                        "client2".equals(account.key()), ts(joined), ts(joined));
                for (String type : List.of("terms", "privacy", "sensitive", "age14")) {
                    consent(userId, type, joined);
                }
                if ("client2".equals(account.key())) {
                    consent(userId, "training_data", joined);
                }
                joined = joined.plusSeconds(60);
            }
        }

        private void consent(UUID userId, String type, Instant at) {
            jdbc.update("insert into consents (id, user_id, type, version, granted_at, created_at) values (?, ?, ?, ?, ?, ?)",
                    SeedIds.at(at), userId, type, CONSENT_VERSION, ts(at), ts(at));
        }

        void profilesAndCredentials() {
            Instant reviewed = daysAgo(110, 15);
            trainee("trainee1", "하늘", "김하늘", "한국상담대학원 상담심리전공", List.of("청년 진로", "취업 스트레스", "자존감"),
                    "취업 준비로 지친 마음을 천천히 정리하도록 돕고 있어요. 이야기를 끝까지 듣는 걸 가장 중요하게 생각해요.");
            trainee("trainee2", "새벽", "이새벽", "마음대학교 상담학과", List.of("대인관계 불안", "학업 스트레스"),
                    "사람 사이에서 생기는 긴장과 불안을 함께 살펴봐요.");
            trainee("trainee3", "노을", "박노을", "한빛대학원 임상상담전공", List.of("가족 관계", "상실·애도"),
                    "가족 안에서 생긴 서운함을 말로 꺼낼 수 있게 도와요.");
            trainee("trainee4", "바다", "최바다", "한국상담대학원 상담심리전공", List.of("직장 번아웃", "생활 리듬", "취업 스트레스"),
                    "지친 일상의 리듬을 다시 세우는 작은 단계부터 같이 찾아요.");
            expert("expert1", "윤 선생님", "윤서연", List.of("직장 번아웃", "청년 진로", "자존감", "대인관계 불안"),
                    "청년기 불안과 진로 고민을 15년 가까이 만나 온 임상심리전문가예요.",
                    "[{\"org\": \"서울마음클리닉\", \"from\": \"2012-03\", \"to\": \"2019-12\"},"
                            + " {\"org\": \"한빛심리상담센터\", \"from\": \"2020-01\", \"to\": null}]");
            expert("expert2", "정 선생님", "정민호", List.of("가족 관계", "연애 갈등"),
                    "관계 속에서 반복되는 패턴을 함께 찾아요.",
                    "[{\"org\": \"마음숲상담연구소\", \"from\": \"2016-05\", \"to\": null}]");

            UUID admin = id("admin");
            for (String key : List.of("trainee1", "trainee2", "trainee4")) {
                credential(key, "enrollment", "상담심리 석사과정 재학 증명", null, "approved", "manual", "none", admin, reviewed);
            }
            credential("trainee3", "enrollment", "임상상담 석사과정 재학 증명", null, "submitted", "manual", "none", null, null);
            credential("expert1", "national", "임상심리사 1급", "2012-000123", "approved", "api", "match", admin, reviewed);
            UUID society = credential("expert2", "society", "상담심리사 1급 (한국상담심리학회)", "SC-2016-0457", "submitted",
                    "ocr", "none", null, null);
            jdbc.update("update credentials set ocr_result = cast(? as jsonb) where id = ?",
                    "{\"cert_name\": \"상담심리사 1급\", \"cert_number\": \"SC-2016-0457\", \"issued_on\": \"2016-02-20\","
                            + " \"confidence\": 0.93}", society);
        }

        private void trainee(String key, String displayName, String realName, String school, List<String> specialties,
                String bio) {
            jdbc.update("""
                    insert into provider_profiles (user_id, track, display_name, real_name_enc, service_label, specialties,
                                                   bio, school_enc)
                    values (?, 'trainee', ?, ?, 'coaching', ?, ?, ?)""",
                    id(key), displayName, codec.encrypt(realName), textArray(specialties), bio, codec.encrypt(school));
        }

        private void expert(String key, String displayName, String realName, List<String> specialties, String bio,
                String career) {
            jdbc.update("""
                    insert into provider_profiles (user_id, track, display_name, real_name_enc, specialties, bio, career)
                    values (?, 'expert', ?, ?, ?, ?, cast(? as jsonb))""",
                    id(key), displayName, codec.encrypt(realName), textArray(specialties), bio, career);
        }

        private UUID credential(String key, String kind, String certName, String certNumber, String status,
                String verifyMethod, String verifyResult, UUID reviewedBy, Instant reviewedAt) {
            UUID credentialId = SeedIds.at(daysAgo(115, 10));
            jdbc.update("""
                    insert into credentials (id, user_id, kind, cert_name, cert_number_enc, issued_on, verify_method,
                                             verify_result, status, reviewed_by, reviewed_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    credentialId, id(key), kind, certName, certNumber == null ? null : codec.encrypt(certNumber),
                    LocalDate.of(2016, 2, 20), verifyMethod, verifyResult, status, reviewedBy, ts(reviewedAt),
                    ts(daysAgo(115, 10)));
            return credentialId;
        }

        // ------------------------------------------------------------ 상담 이력

        void history() {
            UUID client2 = id("client2");
            UUID client3 = id("client3");
            UUID trainee1 = id("trainee1");
            UUID trainee2 = id("trainee2");
            UUID trainee4 = id("trainee4");
            UUID expert1 = id("expert1");
            UUID admin = id("admin");

            // client2 민수: 코칭 3회기 (trainee1 2회, trainee4 1회) → 연계 리포트 종합 시연
            UUID intake2 = intake(client2, "career", daysAgo(23, 21), "none",
                    "졸업하고 1년째 취업 준비 중인데 서류에서 계속 떨어지니까 제가 뭘 해도 안 될 것 같아요. 부모님 얼굴 보기도 미안하고요.",
                    List.of("불안", "무력감", "죄책감"), List.of("파국화", "과잉일반화"), List.of("취업", "서류 탈락", "부모님"));
            UUID m1 = completed("coaching", client2, trainee1, intake2, daysAgo(21, 20), 3, 5, List.of(
                    "c|안녕하세요. 사실 뭘 말해야 할지 잘 모르겠어요.",
                    "p|괜찮아요. 오늘 여기 오기까지 어떤 마음이었는지부터 편하게 얘기해 주셔도 돼요.",
                    "c|서류를 서른 군데 넘게 넣었는데 다 떨어졌어요. 이제는 지원 버튼 누르는 것도 무서워요.",
                    "p|서른 번 넘게 결과를 기다리고 실망하는 걸 반복하셨네요. 그만큼 지칠 만해요.",
                    "c|친구들은 다 붙는데 저만 이러니까 제가 뭔가 모자란 사람 같아요."));
            report(m1, client2, trainee1, daysAgo(21, 21), "none",
                    "반복된 서류 탈락 이후 자기 능력 전체를 부정하는 생각이 커짐",
                    List.of("서류 탈락 반복", "친구들과의 비교"),
                    List.of("서류를 서른 군데 넘게 넣었는데 다 떨어졌어요", "제가 뭔가 모자란 사람 같아요"),
                    List.of("감정 반영", "경험 타당화"), "3 → 5",
                    "취업 준비가 길어지면서 지친 마음을 이야기했어요. 결과와 나 자신을 나눠서 보는 연습을 시작했어요.");
            UUID m2 = completed("coaching", client2, trainee1, intake2, daysAgo(14, 20), 4, 6, List.of(
                    "p|지난주 이후로 지원은 어떻게 하고 계세요?",
                    "c|두 군데 넣었어요. 근데 넣고 나서 하루 종일 메일만 확인했어요.",
                    "p|결과를 기다리는 시간이 특히 힘드신 것 같아요. 그 시간에 어떤 생각이 드세요?",
                    "c|떨어지면 이번에도 역시 나는 안 되는구나, 그런 생각이요.",
                    "p|'역시'라는 말이 붙는 순간 지난 결과들이 한꺼번에 떠오르는 것 같네요."));
            report(m2, client2, trainee1, daysAgo(14, 21), "low",
                    "결과 대기 중 반추가 심해지고 실패를 확신하는 패턴",
                    List.of("지원 후 결과 대기", "메일 반복 확인"),
                    List.of("하루 종일 메일만 확인했어요", "이번에도 역시 나는 안 되는구나"),
                    List.of("소크라테스식 질문", "자동적 사고 탐색"), "4 → 6",
                    "결과를 기다리는 동안 드는 생각을 살펴봤어요. '역시'라는 생각이 들 때 잠깐 멈추는 연습을 해 보기로 했어요.");
            UUID m3 = completed("coaching", client2, trainee4, intake2, daysAgo(7, 21), 3, 4, List.of(
                    "c|요즘은 잠도 잘 못 자요. 새벽까지 공고만 보다가 아침에 겨우 잠들어요.",
                    "p|밤낮이 바뀌면서 몸도 많이 지치셨겠어요.",
                    "c|가끔은 그냥 다 그만두고 어디론가 사라지고 싶다는 생각도 들어요.",
                    "p|그런 생각이 들 만큼 힘드시군요. 그 생각이 들 때 어떤 상황인지 조금 더 여쭤봐도 될까요?",
                    "c|해치고 싶다는 건 아니고요, 그냥 다 내려놓고 쉬고 싶다는 뜻이에요."));
            report(m3, client2, trainee4, daysAgo(7, 22), "medium",
                    "수면 리듬 붕괴와 소진, '사라지고 싶다'는 표현 등장 (구체적 계획은 부인)",
                    List.of("새벽까지 공고 확인", "수면 부족"),
                    List.of("새벽까지 공고만 보다가 아침에 겨우 잠들어요", "다 그만두고 어디론가 사라지고 싶다는 생각도 들어요",
                            "그냥 다 내려놓고 쉬고 싶다는 뜻이에요"),
                    List.of("위험 확인 질문", "생활 리듬 점검"), "3 → 4",
                    "잠이 부족하고 많이 지친 상태를 이야기했어요. 위기 연락처를 함께 확인하고, 전문 상담도 생각해 보기로 했어요.");
            riskEvent("message", null, m3, client2, trainee4, "medium", "[\"passive_ideation\"]",
                    "다 그만두고 어디론가 사라지고 싶다는 생각도 들어요", "acknowledged", daysAgo(7, 21));
            for (UUID sessionId : List.of(m1, m2, m3)) {
                settlementService.createForSession(sessionId, sessionId.equals(m3) ? trainee4 : trainee1, "coaching",
                        coachingPrice, false);
            }

            // client3 하루 × trainee1: 지난 코칭 5회 + 슈퍼비전 5건 (피드백·역량 시연)
            UUID intake3 = intake(client3, "burnout", daysAgo(62, 22), "low",
                    "회사에서 매일 야근하는데 성과가 안 보여서 출근이 너무 싫어요.",
                    List.of("소진", "짜증"), List.of("당위적 사고"), List.of("야근", "성과", "출근"));
            int[] supervised = {60, 53, 46, 39, 32};
            for (int i = 0; i < supervised.length; i++) {
                UUID session = completed("coaching", client3, trainee1, intake3, daysAgo(supervised[i], 20), 4 + i / 2,
                        5 + i / 2, List.of(
                                "c|이번 주도 야근을 네 번이나 했어요.",
                                "p|많이 지치셨겠어요. 그중에 가장 버거웠던 순간은 언제였어요?",
                                "c|팀장님이 퇴근 직전에 일을 주실 때요. 거절을 못 하겠어요.",
                                "p|거절하면 어떤 일이 생길 것 같아서 어려우세요?"));
                settlementService.createForSession(session, trainee1, "coaching", coachingPrice, false);
                supervision(session, trainee1, expert1, i, daysAgo(supervised[i] - 3, 14));
            }

            // client3 × trainee2: 지난 민원 2건 → 스트라이크 2회 (3진 아웃 시연)
            UUID old1 = completed("coaching", client3, trainee2, intake3, daysAgo(100, 20), 5, 4, List.of(
                    "p|조금 늦어서 죄송해요. 바로 시작할게요."));
            UUID old2 = completed("coaching", client3, trainee2, intake3, daysAgo(80, 20), 5, 5, List.of(
                    "p|그건 결국 마음먹기에 달린 거예요."));
            UUID c1 = complaint(old1, client3, trainee2, "client", "late_noshow", "15분 늦게 들어오셨어요.", "decided",
                    "trainee_fault", admin, daysAgo(99, 11));
            UUID c2 = complaint(old2, client3, trainee2, "client", "insincere", "제 얘기를 제대로 안 듣는 느낌이었어요.",
                    "decided", "trainee_fault", admin, daysAgo(79, 11));
            strike(trainee2, c1, 1, "warning_hold", null, daysAgo(98, 12));
            strike(trainee2, c2, 2, "suspend_2w", daysAgo(64, 12), daysAgo(78, 12));

            // 판정 대기 민원 3건 + 열린 위기 이벤트 2건 (어드민 M 화면 시연)
            UUID recent1 = completed("coaching", client3, trainee2, intake3, daysAgo(5, 20), 4, 3, List.of(
                    "p|힘든 건 기도하면서 견디면 다 지나가요.",
                    "c|저는 종교가 없어서 그런 얘기는 좀 불편해요.",
                    "c|요즘은 정말 버티기 힘들어서 아무것도 하기 싫어요."));
            UUID recent2 = noShow(client3, trainee2, daysAgo(3, 20));
            UUID recent3 = completed("coaching", client3, trainee2, intake3, daysAgo(2, 20), 3, 3, List.of(
                    "c|환불 안 해 주시면 리뷰에 다 쓸 거예요.",
                    "c|진짜 다 끝내고 싶어요. 오늘 밤엔 정말 모르겠어요."));
            complaint(recent1, client3, trainee2, "client", "religion_moralizing", "종교적인 조언을 계속하셨어요.", "received",
                    null, null, daysAgo(5, 22));
            complaint(recent2, null, trainee2, "auto_no_show", "late_noshow", null, "received", null, null,
                    daysAgo(3, 21));
            complaint(recent3, trainee2, client3, "provider", "refund_abuse", "환불을 요구하며 리뷰로 압박했어요.", "received",
                    null, null, daysAgo(2, 22));
            for (UUID sessionId : List.of(old1, old2, recent1, recent3)) {
                settlementService.createForSession(sessionId, trainee2, "coaching", coachingPrice, false);
            }
            riskEvent("message", null, recent3, client3, trainee2, "high", "[\"suicidal_intent\", \"time_reference\"]",
                    "진짜 다 끝내고 싶어요. 오늘 밤엔 정말 모르겠어요.", "open", daysAgo(2, 20).plusSeconds(900));
            UUID reIntake = intake(client3, "self", now.minus(Duration.ofHours(6)), "high",
                    "요즘 아무 의미가 없어요. 사라지면 편할 것 같다는 생각을 자주 해요.",
                    List.of("공허감", "절망감"), List.of("흑백논리"), List.of("의미 없음", "사라지고 싶음"));
            riskEvent("intake", reIntake, null, client3, null, "high", "[\"passive_ideation\", \"hopelessness\"]",
                    "사라지면 편할 것 같다는 생각을 자주 해요.", "open", now.minus(Duration.ofHours(6)));

            notification(trainee1, "supervision_submitted", "슈퍼비전 피드백이 도착했어요",
                    "윤 선생님이 피드백을 남겼어요.", "/t/supervisions", daysAgo(29, 14));
            notification(client2, "report_ready", "상담 리포트가 준비됐어요", "지난 상담 내용을 확인해 보세요.",
                    "/c/sessions", daysAgo(7, 22));
        }

        private UUID intake(UUID clientId, String category, Instant at, String risk, String text, List<String> emotions,
                List<String> distortions, List<String> keywords) {
            UUID intakeId = SeedIds.at(at);
            jdbc.update("""
                    insert into intakes (id, client_id, category, text_enc, preferred_times, ai_tags, risk_level, created_at)
                    values (?, ?, ?, ?, '{evening,night}', cast(? as jsonb), ?, ?)""",
                    intakeId, clientId, category, codec.encrypt(text),
                    json(Map.of("emotions", emotions, "distortions", distortions, "keywords", keywords)), risk, ts(at));
            return intakeId;
        }

        /** 끝난 코칭 회기: 슬롯(booked) + 세션 + 결제 + 기분 체크 + 대화. 대화 줄은 "c|" 내담자, "p|" 상담사. */
        private UUID completed(String track, UUID clientId, UUID providerId, UUID intakeId, Instant start,
                int moodBefore, int moodAfter, List<String> lines) {
            Instant end = start.plus(Duration.ofMinutes(coachingMinutes));
            UUID sessionId = session(track, clientId, providerId, intakeId, start, end, "completed");
            jdbc.update("""
                    update sessions set client_joined_at = ?, provider_joined_at = ?, started_at = ?, ended_at = ?,
                                        ended_by = 'provider', rating = 5
                     where id = ?""", ts(start), ts(start), ts(start), ts(end), sessionId);
            jdbc.update("insert into mood_checks (id, session_id, client_id, phase, score, created_at) values (?, ?, ?, 'before', ?, ?)",
                    SeedIds.at(start), sessionId, clientId, moodBefore, ts(start));
            jdbc.update("insert into mood_checks (id, session_id, client_id, phase, score, created_at) values (?, ?, ?, 'after', ?, ?)",
                    SeedIds.at(end), sessionId, clientId, moodAfter, ts(end));
            Instant at = start.plusSeconds(60);
            for (String line : lines) {
                UUID sender = line.startsWith("c|") ? clientId : providerId;
                jdbc.update("""
                        insert into messages (id, session_id, sender_id, body_enc, client_msg_id, created_at)
                        values (?, ?, ?, ?, ?, ?)""",
                        SeedIds.at(at), sessionId, sender, codec.encrypt(line.substring(2)), "seed-" + UUID.randomUUID(),
                        ts(at));
                at = at.plusSeconds(90);
            }
            return sessionId;
        }

        private UUID noShow(UUID clientId, UUID providerId, Instant start) {
            return session("coaching", clientId, providerId, null, start, start.plus(Duration.ofMinutes(coachingMinutes)),
                    "no_show");
        }

        private UUID session(String track, UUID clientId, UUID providerId, UUID intakeId, Instant start, Instant end,
                String status) {
            Instant booked = start.minus(Duration.ofDays(2));
            UUID slotId = SeedIds.at(booked.minusSeconds(3600));
            UUID sessionId = SeedIds.at(booked);
            jdbc.update("""
                    insert into availability_slots (id, provider_id, start_at, end_at, status, session_id, created_at)
                    values (?, ?, ?, ?, 'booked', ?, ?)""",
                    slotId, providerId, ts(start), ts(end), sessionId, ts(booked.minusSeconds(3600)));
            jdbc.update("""
                    insert into sessions (id, track, client_id, provider_id, intake_id, slot_id, scheduled_start,
                                          scheduled_end, status, price, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    sessionId, track, clientId, providerId, intakeId, slotId, ts(start), ts(end), status, coachingPrice,
                    ts(booked), ts(end));
            jdbc.update("""
                    insert into payments (id, session_id, client_id, amount, provider, order_id, status, idempotency_key,
                                          paid_at, created_at)
                    values (?, ?, ?, ?, 'mock', ?, 'paid', ?, ?, ?)""",
                    SeedIds.at(booked), sessionId, clientId, coachingPrice, "seed-" + sessionId, "seed-" + sessionId,
                    ts(booked), ts(booked));
            sessionCount++;
            return sessionId;
        }

        /** 세션 리포트 (TRD 11장 content 형식 + 16-2-1 digest). */
        private void report(UUID sessionId, UUID clientId, UUID traineeId, Instant readyAt, String riskLevel,
                String chiefComplaint, List<String> triggers, List<String> keyQuotes, List<String> interventions,
                String moodShift, String clientSummary) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("chief_complaint", chiefComplaint);
            content.put("distortions", List.of(Map.of("name", "과잉일반화", "evidence", keyQuotes.getLast())));
            content.put("triggers", triggers);
            content.put("defenses", List.of("회피"));
            content.put("red_flag", Map.of("level", riskLevel, "evidence", "medium".equals(riskLevel)
                    ? keyQuotes.get(1) : ""));
            Map<String, Object> digest = new LinkedHashMap<>();
            digest.put("issues", List.of(chiefComplaint));
            digest.put("key_quotes", keyQuotes);
            digest.put("mood_shift", moodShift);
            digest.put("risk_level", riskLevel);
            digest.put("trainee_interventions", interventions);
            jdbc.update("""
                    insert into reports (id, scope, session_id, client_id, trainee_id, status, content_enc,
                                         client_summary_enc, digest_enc, risk_level, model, prompt_version, attempts,
                                         ready_at, created_at)
                    values (?, 'session', ?, ?, ?, 'ready', ?, ?, ?, ?, 'seed', 'seed-v1', 1, ?, ?)""",
                    SeedIds.at(readyAt), sessionId, clientId, traineeId, codec.encrypt(json(content)),
                    codec.encrypt(clientSummary), codec.encrypt(json(digest)), riskLevel, ts(readyAt), ts(readyAt));
        }

        private void riskEvent(String source, UUID intakeId, UUID sessionId, UUID clientId, UUID providerId,
                String level, String ruleHits, String evidence, String status, Instant at) {
            boolean acked = !"open".equals(status);
            jdbc.update("""
                    insert into risk_events (id, source, intake_id, session_id, client_id, provider_id, level, rule_hits,
                                             evidence_enc, status, acked_by, acked_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)""",
                    SeedIds.at(at), source, intakeId, sessionId, clientId, providerId, level, ruleHits,
                    codec.encrypt(evidence), status, acked ? providerId : null, acked ? ts(at.plusSeconds(120)) : null,
                    ts(at));
        }

        /** 수련생 요청 슈퍼비전(kind=request) 제출 완료. 회차가 갈수록 점수가 조금씩 오른다 (역량 차트용). */
        private void supervision(UUID sessionId, UUID traineeId, UUID expertId, int index, Instant submittedAt) {
            Instant requested = submittedAt.minus(Duration.ofDays(2));
            UUID requestId = SeedIds.at(requested);
            jdbc.update("""
                    insert into supervision_requests (id, trainee_id, session_id, preferred_expert_id, assigned_expert_id,
                                                      question_enc, fee, status, accepted_at, created_at)
                    values (?, ?, ?, ?, ?, ?, 0, 'completed', ?, ?)""",
                    requestId, traineeId, sessionId, expertId, expertId,
                    codec.encrypt(SUPERVISION_QUESTIONS.get(index)), ts(requested.plusSeconds(3600)), ts(requested));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("well_done", SUPERVISION_WELL_DONE.get(index));
            content.put("blind_spot", SUPERVISION_BLIND_SPOT.get(index));
            content.put("alternative_responses", List.of(Map.of(
                    "session_id", sessionId.toString(),
                    "quote", "거절하면 어떤 일이 생길 것 같아서 어려우세요?",
                    "suggestion", SUPERVISION_SUGGESTION.get(index))));
            content.put("risk_comment", "이번 회기에서는 위험 신호가 보이지 않았고, 확인 질문의 시점도 적절했어요.");
            content.put("next_action", SUPERVISION_NEXT.get(index));
            int base = 2 + (index + 1) / 2;
            jdbc.update("""
                    insert into supervisions (id, kind, request_id, coaching_session_id, expert_id, trainee_id, content_enc,
                                              risk_rating, score_rapport, score_empathy, score_risk, score_structure,
                                              status, due_at, submitted_at, read_at, created_at)
                    values (?, 'request', ?, ?, ?, ?, ?, 'appropriate', ?, ?, ?, ?, 'submitted', ?, ?, ?, ?)""",
                    SeedIds.at(requested.plusSeconds(3600)), requestId, sessionId, expertId, traineeId,
                    codec.encrypt(json(content)), Math.min(5, base + 1), Math.min(5, base), Math.min(5, base),
                    Math.min(5, base - 1 + index / 3), ts(requested.plus(Duration.ofDays(3))), ts(submittedAt),
                    ts(submittedAt.plus(Duration.ofHours(5))), ts(requested.plusSeconds(3600)));
        }

        private UUID complaint(UUID sessionId, UUID reporterId, UUID targetId, String source, String category,
                String description, String status, String decision, UUID decidedBy, Instant at) {
            UUID complaintId = SeedIds.at(at);
            boolean decided = "decided".equals(status);
            jdbc.update("""
                    insert into complaints (id, session_id, reporter_id, target_id, source, category, description_enc,
                                            auto_evidence, status, decision, decision_reason, decided_by, decided_at,
                                            created_at)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)""",
                    complaintId, sessionId, reporterId, targetId, source, category,
                    description == null ? null : codec.encrypt(description),
                    "auto_no_show".equals(source) ? "{\"provider_joined\": false, \"waited_min\": 15}" : null,
                    status, decision, decided ? "대화 기록으로 사실을 확인했어요." : null, decidedBy,
                    decided ? ts(at.plus(Duration.ofDays(1))) : null, ts(at));
            return complaintId;
        }

        private void strike(UUID traineeId, UUID complaintId, int seq, String action, Instant suspendedUntil,
                Instant at) {
            jdbc.update("""
                    insert into strikes (id, trainee_id, complaint_id, seq, action, suspended_until, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)""",
                    SeedIds.at(at), traineeId, complaintId, seq, action, ts(suspendedUntil), ts(at));
        }

        private void notification(UUID userId, String type, String title, String body, String link, Instant at) {
            jdbc.update("""
                    insert into notifications (id, user_id, type, title, body, link, payload, created_at)
                    values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)""",
                    SeedIds.at(at), userId, type, title, body, link, ts(at));
        }

        /** 활동 중인 상담사별 앞으로 7일, 저녁 두 타임 (KST 19시·21시). */
        int upcomingSlots() {
            int count = 0;
            int expertMinutes = priceConfigService.trackPricing(PriceConfigService.PROFESSIONAL).durationMinutes();
            for (String key : List.of("trainee1", "trainee2", "trainee4", "expert1")) {
                int minutes = key.startsWith("expert") ? expertMinutes : coachingMinutes;
                for (int day = 1; day <= 7; day++) {
                    for (int hour : new int[] {19, 21}) {
                        Instant start = daysAgo(-day, hour);
                        jdbc.update("""
                                insert into availability_slots (id, provider_id, start_at, end_at, status, created_at)
                                values (?, ?, ?, ?, 'open', ?)""",
                                SeedIds.at(now), id(key), ts(start), ts(start.plus(Duration.ofMinutes(minutes))), ts(now));
                        count++;
                    }
                }
            }
            return count;
        }

        /** 오늘(KST)에서 days일 전 hour시. 음수면 미래. */
        private Instant daysAgo(int days, int hour) {
            ZonedDateTime today = now.atZone(KST).toLocalDate().atStartOfDay(KST);
            return today.minusDays(days).plusHours(hour).toInstant();
        }

        private String json(Object value) {
            return jsonMapper.writeValueAsString(value);
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String[] textArray(List<String> values) {
        return values.toArray(String[]::new);
    }

    // ---------------------------------------------------------------- 합성 문장 (팀 작성)

    private static final List<String> SUPERVISION_QUESTIONS = List.of(
            "내담자가 거절을 못 하는 이유를 탐색할 때 너무 빨리 들어간 것 같아요. 속도를 어떻게 조절하면 좋을까요?",
            "야근 이야기가 반복될 때 같은 질문을 되풀이하게 돼요. 다른 접근이 있을까요?",
            "내담자가 '원래 그런 회사'라고 할 때 어떻게 반응해야 할지 막막했어요.",
            "회기 마무리에서 과제를 제안했는데 부담스러워하는 것 같았어요.",
            "이번 회기에서 처음으로 감정 단어가 나왔는데, 더 머물렀어야 했을까요?");

    private static final List<String> SUPERVISION_WELL_DONE = List.of(
            "회기 초반에 내담자의 피로를 먼저 알아봐 주면서 바로 문제 해결로 넘어가지 않은 점이 좋았어요. 내담자가 '이번 주도'라고 말할 때 반복된 지침을 짚어 준 반영이 정확했고, 덕분에 내담자가 구체적인 장면을 꺼낼 수 있었어요.",
            "같은 주제가 반복되는 상황에서도 짜증이나 조급함 없이 일정한 톤을 유지했어요. 특히 내담자가 말을 멈췄을 때 침묵을 기다려 준 장면이 인상적이었고, 그 뒤에 나온 이야기가 회기의 핵심이 되었어요.",
            "'원래 그런 회사'라는 말에 바로 반박하지 않고 그렇게 느끼게 된 경험을 물어본 점이 좋았어요. 내담자의 체념을 판단하지 않고 받아 준 덕분에 관계가 흔들리지 않았고, 회기 후반까지 대화가 이어졌어요.",
            "과제를 제안하기 전에 내담자가 이번 주에 해 볼 수 있는 일의 크기를 먼저 물어본 점이 좋아졌어요. 지난 회기보다 질문이 짧아졌고, 내담자의 말을 요약해서 돌려주는 횟수도 자연스럽게 늘었어요.",
            "처음 나온 감정 단어 '억울하다'를 놓치지 않고 다시 불러 준 점이 아주 좋았어요. 다섯 회기를 거치며 공감 반응이 구체적으로 바뀌었고, 내담자도 스스로 감정을 이름 붙이는 장면이 늘었어요.");

    private static final List<String> SUPERVISION_BLIND_SPOT = List.of(
            "거절이 어려운 이유를 묻는 질문이 회기 15분 만에 나와서, 내담자가 아직 충분히 안전하다고 느끼기 전이었을 수 있어요. 이 시점에는 원인을 묻기보다 퇴근 직전에 일을 받을 때 몸과 마음에 어떤 반응이 오는지 먼저 함께 살펴보는 편이 좋아요. 원인 탐색은 내담자가 스스로 패턴을 말하기 시작할 때 따라가도 늦지 않아요.",
            "야근 이야기가 나올 때마다 '그때 어떤 생각이 들었어요?'라는 같은 형태의 질문이 세 번 반복됐어요. 내담자 입장에서는 같은 자리를 맴도는 느낌이 들 수 있어요. 반복되는 주제일수록 지난 회기와 달라진 점이나 예외적인 순간을 묻는 식으로 질문의 각도를 바꿔 보면 대화가 앞으로 나아가요.",
            "체념하는 말 뒤에 숨어 있는 기대나 서운함을 다루지 못하고 회기가 정보 확인 위주로 흘렀어요. '원래 그렇다'는 말은 더 이상 실망하지 않으려는 보호일 때가 많아요. 그 말을 하게 된 계기가 된 사건이 있었는지, 그때 무엇을 바랐는지 한 번 더 물어봐 주면 내담자의 진짜 바람에 닿을 수 있어요.",
            "과제를 제안할 때 '해 보세요'라는 표현이 내담자에게 숙제처럼 들렸을 수 있어요. 내담자가 잠시 대답을 망설인 장면에서 부담을 확인하지 않고 다음 주제로 넘어갔어요. 과제는 내담자가 직접 고르게 하거나, 하지 못해도 괜찮다는 말을 함께 붙이면 실패 경험이 되지 않고 다음 회기의 이야깃거리가 돼요.",
            "감정 단어가 나온 직후 바로 해결 방법으로 넘어가면서 그 감정에 머무는 시간이 짧았어요. 처음 감정이 나왔을 때는 그 감정이 언제부터 있었는지, 몸에서는 어떻게 느껴지는지 조금 더 머물러 주는 게 좋아요. 다음 회기에서 '지난번에 억울하다고 하셨던 마음'을 먼저 꺼내 주면 이어서 깊이 들어갈 수 있어요.");

    private static final List<String> SUPERVISION_SUGGESTION = List.of(
            "퇴근 직전에 일을 받으면 그 순간 어떤 느낌이 드세요?",
            "지난주랑 비교하면 이번 주에 조금이라도 달랐던 날이 있었어요?",
            "'원래 그렇다'고 생각하게 된 일이 있었을까요?",
            "이번 주에 해 볼 만한 작은 일을 하나 골라 보실래요? 못 해도 괜찮아요.",
            "억울하다는 마음이 들 때 몸에서는 어떻게 느껴지세요?");

    private static final List<String> SUPERVISION_NEXT = List.of(
            "다음 회기에는 원인을 묻기 전에 감정과 몸의 반응을 먼저 탐색해 보세요.",
            "반복 주제가 나오면 예외 질문을 한 번 시도해 보세요.",
            "체념 표현 뒤의 기대를 묻는 질문을 한 번 해 보세요.",
            "과제는 내담자가 고르게 하고, 다음 회기 시작에 부담 없이 확인해 보세요.",
            "지난 회기 감정 단어로 다음 회기를 열어 보세요.");
}
