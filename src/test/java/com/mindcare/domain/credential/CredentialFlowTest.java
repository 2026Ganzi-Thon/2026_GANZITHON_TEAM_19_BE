package com.mindcare.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class CredentialFlowTest {

    private static final String PROFILE = """
            {"display_name":"하늘","real_name":"김하늘","service_label":"coaching","specialties":["자존감"]}""";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User trainee;
    User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        admin = saveUser("admin", UserRole.ADMIN, UserStatus.ACTIVE);
    }

    @Test
    void 수련생_서류_제출부터_어드민_승인까지() throws Exception {
        mockMvc.perform(json(as(trainee, put("/me/provider-profile")), PROFILE)).andExpect(status().isOk());
        String fileKey = uploadUrl(trainee, "application/pdf");

        String created = mockMvc.perform(json(as(trainee, post("/me/credentials")), """
                        {"kind":"enrollment","cert_name":"상담심리 석사 재학","file_key":"%s"}""".formatted(fileKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.has_file").value(true))
                .andExpect(jsonPath("$.verify_method").value("manual"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(trainee, post("/me/credentials/submit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted_count").value(1))
                .andExpect(jsonPath("$.user_status").value("pending_review"));
        em.flush();
        assertThat(count("select count(*) from notifications where user_id = ? and type = 'credential_submitted'",
                admin)).isEqualTo(1);

        mockMvc.perform(as(admin, get("/admin/credentials")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.user_id == '%s')].display_name".formatted(trainee.getId()))
                        .value("하늘"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());

        mockMvc.perform(as(admin, get("/admin/credentials/" + trainee.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.real_name").value("김하늘"))
                .andExpect(jsonPath("$.credentials[0].id").value(JsonPath.<String>read(created, "$.id")))
                .andExpect(jsonPath("$.credentials[0].file_url").isNotEmpty())
                .andExpect(jsonPath("$.credentials[0].status").value("submitted"));
        em.flush();
        assertThat(count("select count(*) from audit_logs where actor_id = ? and action = 'view_credential'", admin))
                .isEqualTo(1);

        mockMvc.perform(json(as(admin, post("/admin/credentials/" + trainee.getId() + "/decision")),
                        "{\"decision\":\"approve\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided_count").value(1))
                .andExpect(jsonPath("$.user_status").value("active"));
        em.flush();
        assertThat(count("select count(*) from notifications where user_id = ? and type = 'credential_decided'",
                trainee)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, trainee.getId()))
                .isEqualTo("active");

        // 승인되면 내담자가 공개 프로필에서 자격 이름을 본다
        User client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);
        mockMvc.perform(as(client, get("/providers/" + trainee.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cert_names[0]").value("상담심리 석사 재학"));
    }

    @Test
    void 반려하면_사유가_보이고_고쳐서_다시_낼_수_있다() throws Exception {
        String credentialId = submitOne(trainee);

        mockMvc.perform(json(as(admin, post("/admin/credentials/" + trainee.getId() + "/decision")),
                        "{\"decision\":\"reject\",\"reason\":\"서류가 흐려서 확인이 안 됩니다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_status").value("rejected"));
        em.flush();
        em.clear();
        User rejected = userRepository.findById(trainee.getId()).orElseThrow();

        mockMvc.perform(as(rejected, get("/me/credentials")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("rejected"))
                .andExpect(jsonPath("$[0].reject_reason").value("서류가 흐려서 확인이 안 됩니다"));

        mockMvc.perform(json(as(rejected, patch("/me/credentials/" + credentialId)),
                        "{\"cert_name\":\"상담심리 석사 재학 (2학기)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.user_corrected").value(true))
                .andExpect(jsonPath("$.reject_reason").isEmpty());

        mockMvc.perform(as(rejected, post("/me/credentials/submit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_status").value("pending_review"));
    }

    @Test
    void 반려는_사유가_없으면_422() throws Exception {
        submitOne(trainee);

        mockMvc.perform(json(as(admin, post("/admin/credentials/" + trainee.getId() + "/decision")),
                        "{\"decision\":\"reject\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("reason"));
    }

    @Test
    void 제출한_자격이_없으면_승인할_수_없다() throws Exception {
        mockMvc.perform(json(as(admin, post("/admin/credentials/" + trainee.getId() + "/decision")),
                        "{\"decision\":\"approve\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 프로필이_없으면_제출은_409() throws Exception {
        String fileKey = uploadUrl(trainee, "image/png");
        mockMvc.perform(json(as(trainee, post("/me/credentials")), """
                        {"kind":"completion","cert_name":"수료증","file_key":"%s"}""".formatted(fileKey)))
                .andExpect(status().isCreated());

        mockMvc.perform(as(trainee, post("/me/credentials/submit")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("profile_required"));
    }

    @Test
    void 제출할_draft가_없으면_409() throws Exception {
        mockMvc.perform(json(as(trainee, put("/me/provider-profile")), PROFILE)).andExpect(status().isOk());

        mockMvc.perform(as(trainee, post("/me/credentials/submit")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("no_draft"));
    }

    @Test
    void 업로드는_PDF_JPG_PNG만_10MB까지() throws Exception {
        mockMvc.perform(json(as(trainee, post("/me/credentials/upload-url")),
                        "{\"content_type\":\"image/gif\",\"size\":1000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("unsupported_type"));
        mockMvc.perform(json(as(trainee, post("/me/credentials/upload-url")),
                        "{\"content_type\":\"application/pdf\",\"size\":10485761}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 다른_사람_폴더의_파일이나_안_올린_파일은_못_쓴다() throws Exception {
        User other = saveUser("other", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        String othersKey = uploadUrl(other, "application/pdf");

        mockMvc.perform(json(as(trainee, post("/me/credentials")), """
                        {"kind":"enrollment","cert_name":"재학증명서","file_key":"%s"}""".formatted(othersKey)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("invalid"));
        mockMvc.perform(json(as(trainee, post("/me/credentials")), """
                        {"kind":"enrollment","cert_name":"재학증명서",
                         "file_key":"credentials/%s/never-uploaded.pdf"}""".formatted(trainee.getId())))
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("not_uploaded"));
    }

    @Test
    void 수련생은_국가자격을_등록할_수_없다() throws Exception {
        mockMvc.perform(json(as(trainee, post("/me/credentials")), """
                        {"kind":"national","cert_name":"임상심리사 1급","cert_number":"12345678","verify":true}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("not_allowed_for_role"));
    }

    @Test
    void 전문가_국가자격은_서류_없이_진위_확인한다() throws Exception {
        User expert = saveUser("expert", UserRole.EXPERT, UserStatus.PENDING_REVIEW);

        mockMvc.perform(json(as(expert, post("/me/credentials")), """
                        {"kind":"national","cert_name":"임상심리사 1급","cert_number":"2026123456","verify":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verify_method").value("api"))
                .andExpect(jsonPath("$.verify_result").value("match"))
                .andExpect(jsonPath("$.cert_number_masked").value("******3456"));
        mockMvc.perform(json(as(expert, post("/me/credentials")), """
                        {"kind":"national","cert_name":"임상심리사 2급","cert_number":"2026120000","verify":true}"""))
                .andExpect(jsonPath("$.verify_result").value("mismatch"));
    }

    @Test
    void 자격번호는_DB에_암호화되어_저장된다() throws Exception {
        User expert = saveUser("expert", UserRole.EXPERT, UserStatus.PENDING_REVIEW);
        mockMvc.perform(json(as(expert, post("/me/credentials")), """
                        {"kind":"national","cert_name":"임상심리사 1급","cert_number":"2026123456","verify":true}"""))
                .andExpect(status().isCreated());
        em.flush();

        byte[] enc = jdbc.queryForObject("select cert_number_enc from credentials where user_id = ?", byte[].class,
                expert.getId());
        assertThat(new String(enc, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("2026123456");
    }

    @Test
    void 남의_자격은_고칠_수_없다() throws Exception {
        String credentialId = submitOne(trainee);
        User other = saveUser("other", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        mockMvc.perform(json(as(other, patch("/me/credentials/" + credentialId)), "{\"cert_name\":\"바꿈\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_RESOURCE"));
    }

    @Test
    void 제출된_자격은_고칠_수_없다() throws Exception {
        String credentialId = submitOne(trainee);

        mockMvc.perform(json(as(trainee, patch("/me/credentials/" + credentialId)), "{\"cert_name\":\"바꿈\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 어드민이_아니면_심사_API를_쓸_수_없다() throws Exception {
        mockMvc.perform(as(trainee, get("/admin/credentials")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(as(trainee, get("/admin/credentials/" + trainee.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 내담자는_자격_API를_쓸_수_없다() throws Exception {
        User client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);

        mockMvc.perform(as(client, get("/me/credentials")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    /** 프로필 작성 → 서류 업로드 → 등록 → 제출. 등록된 자격 id를 돌려준다. */
    private String submitOne(User user) throws Exception {
        mockMvc.perform(json(as(user, put("/me/provider-profile")), PROFILE)).andExpect(status().isOk());
        String fileKey = uploadUrl(user, "application/pdf");
        String created = mockMvc.perform(json(as(user, post("/me/credentials")), """
                        {"kind":"enrollment","cert_name":"상담심리 석사 재학","file_key":"%s"}""".formatted(fileKey)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(as(user, post("/me/credentials/submit"))).andExpect(status().isOk());
        return JsonPath.read(created, "$.id");
    }

    private String uploadUrl(User user, String contentType) throws Exception {
        String response = mockMvc.perform(json(as(user, post("/me/credentials/upload-url")),
                        "{\"content_type\":\"%s\",\"size\":204800}".formatted(contentType)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expires_in").value(300))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.file_key");
    }

    private int count(String sql, User user) {
        return jdbc.queryForObject(sql, Integer.class, user.getId());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, UserRole role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-credential-" + name)
                .role(role)
                .nickname("자격" + name)
                .status(status)
                .build());
    }
}
