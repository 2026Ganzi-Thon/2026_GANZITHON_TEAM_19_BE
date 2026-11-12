package com.mindcare.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import com.mindcare.global.crypto.SensitiveTextCodec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** TRD 19-3 시드. Firebase 키가 없어서 계정은 uid만 쓰고, DB 내용과 다시 실행했을 때의 동작을 본다. */
@SpringBootTest
@Transactional
class DemoSeederTest {

    @Autowired DemoSeeder demoSeeder;
    @Autowired JdbcTemplate jdbc;
    @Autowired SensitiveTextCodec codec;
    @Autowired UserRepository userRepository;
    @Autowired WebApplicationContext context;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        ReflectionTestUtils.setField(demoSeeder, "purgeUids", "");
    }

    @Test
    void 시연_계정과_상태가_TRD대로_만들어진다() {
        demoSeeder.seed();

        assertThat(userStatus("demo-client1")).isEqualTo("active");
        assertThat(userStatus("demo-trainee1")).isEqualTo("active");
        assertThat(userStatus("demo-trainee3")).isEqualTo("pending_review");
        assertThat(userStatus("demo-expert1")).isEqualTo("active");
        assertThat(userStatus("demo-expert2")).isEqualTo("pending_review");
        assertThat(jdbc.queryForObject("select role from users where firebase_uid = 'demo-admin'", String.class))
                .isEqualTo("admin");
        assertThat(count("select count(*) from users where firebase_uid like 'demo-%'")).isEqualTo(10);
        // 가입 필수 동의 4종
        assertThat(count("""
                select count(*) from consents c join users u on u.id = c.user_id
                 where u.firebase_uid = 'demo-client1'""")).isEqualTo(4);
    }

    @Test
    void 민수는_코칭_3회기와_세션_리포트_3개가_있다() {
        demoSeeder.seed();
        UUID client2 = userId("demo-client2");

        assertThat(count("select count(*) from sessions where client_id = ? and track = 'coaching' and status = 'completed'",
                client2)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                select u.firebase_uid from sessions s join users u on u.id = s.provider_id
                 where s.client_id = ? order by s.scheduled_start""", String.class, client2))
                .containsExactly("demo-trainee1", "demo-trainee1", "demo-trainee4");
        assertThat(count("select count(*) from reports where client_id = ? and scope = 'session' and status = 'ready'",
                client2)).isEqualTo(3);
        // 3회기에 medium 1건
        assertThat(jdbc.queryForList("select level from risk_events where client_id = ?", String.class, client2))
                .containsExactly("medium");
        // 연계 리포트 재료(digest)가 TRD 16-2-1 형식으로 복호화된다
        byte[] digest = jdbc.queryForObject("""
                select r.digest_enc from reports r join sessions s on s.id = r.session_id
                 where r.client_id = ? order by s.scheduled_start limit 1""", byte[].class, client2);
        assertThat(codec.decrypt(digest)).contains("\"key_quotes\"", "\"mood_shift\"", "\"trainee_interventions\"");
    }

    @Test
    void 슈퍼비전_스트라이크_민원_위기_슬롯이_있다() {
        demoSeeder.seed();

        assertThat(count("select count(*) from supervisions where trainee_id = ? and status = 'submitted'",
                userId("demo-trainee1"))).isEqualTo(5);
        assertThat(count("select count(*) from strikes where trainee_id = ? and revoked_at is null",
                userId("demo-trainee2"))).isEqualTo(2);
        assertThat(count("""
                select count(*) from complaints c join users u on u.id = c.target_id
                 where u.firebase_uid like 'demo-%' and c.status = 'received'""")).isEqualTo(3);
        assertThat(count("""
                select count(*) from risk_events r join users u on u.id = r.client_id
                 where u.firebase_uid like 'demo-%' and r.status = 'open'""")).isEqualTo(2);
        assertThat(count("""
                select count(*) from availability_slots a join users u on u.id = a.provider_id
                 where u.firebase_uid like 'demo-%' and a.status = 'open' and a.start_at > now()""")).isEqualTo(56);
        assertThat(count("""
                select count(*) from settlements st join users u on u.id = st.provider_id
                 where u.firebase_uid like 'demo-%'""")).isEqualTo(12);
    }

    @Test
    void 다시_실행해도_중복되지_않는다() {
        DemoSeeder.Result first = demoSeeder.seed();
        long sessions = count("select count(*) from sessions");

        DemoSeeder.Result second = demoSeeder.seed();

        assertThat(second).isEqualTo(first);
        assertThat(count("select count(*) from sessions")).isEqualTo(sessions);
        assertThat(count("select count(*) from users where firebase_uid like 'demo-%'")).isEqualTo(10);
    }

    @Test
    void 실제_가입자는_건드리지_않고_지정한_계정만_같이_지운다() {
        User real = saveUser("uid-real-user", "진짜가입자");
        User leftover = saveUser("uid-old-test", "운영테스트계정");
        ReflectionTestUtils.setField(demoSeeder, "purgeUids", "uid-old-test");

        demoSeeder.seed();
        demoSeeder.seed();

        assertThat(count("select count(*) from users where id = ?", real.getId())).isEqualTo(1);
        assertThat(count("select count(*) from users where id = ?", leftover.getId())).isZero();
    }

    @Test
    void 시드_계정으로_로그인하면_화면_데이터가_나온다() throws Exception {
        demoSeeder.seed();

        mockMvc.perform(as("demo-client2", get("/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("민수"));
        mockMvc.perform(as("demo-admin", get("/admin/credentials")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(as("demo-trainee1", get("/me/settlements")))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder as(String uid, MockHttpServletRequestBuilder request) {
        String token = "token-" + uid;
        given(tokenVerifier.verify(token)).willReturn(uid);
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String uid, String nickname) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(UserRole.CLIENT).nickname(nickname).status(UserStatus.ACTIVE).build());
    }

    private String userStatus(String uid) {
        return jdbc.queryForObject("select status from users where firebase_uid = ?", String.class, uid);
    }

    private UUID userId(String uid) {
        return jdbc.queryForObject("select id from users where firebase_uid = ?", UUID.class, uid);
    }

    private long count(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.getFirst();
    }
}
