package com.mindcare.domain.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.global.auth.TokenVerifier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** AUTH-02 약관, PUB-01 위기 연락처 (기본값), 가입 시 약관 버전 확인. */
@SpringBootTest
@Transactional
class PublicTermsTest {

    @Autowired WebApplicationContext context;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 약관은_로그인_없이_필수_4개_선택_2개_순서로_나온다() throws Exception {
        mockMvc.perform(get("/public/terms"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("max-age=60")))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].type").value("terms"))
                .andExpect(jsonPath("$[0].version").value("1.0"))
                .andExpect(jsonPath("$[0].required").value(true))
                .andExpect(jsonPath("$[0].body_md").value(Matchers.startsWith("# MindCare 이용약관")))
                .andExpect(jsonPath("$[2].type").value("sensitive"))
                .andExpect(jsonPath("$[2].body_md").value(Matchers.containsString("국외 이전")))
                .andExpect(jsonPath("$[4].type").value("training_data"))
                .andExpect(jsonPath("$[4].required").value(false));
    }

    @Test
    void 옛_버전_약관으로_가입하면_422() throws Exception {
        given(tokenVerifier.verify("token-old-terms")).willReturn("uid-old-terms");

        mockMvc.perform(post("/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-old-terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"client","nickname":"옛약관동의",
                                 "consents":[{"type":"terms","version":"0.9"},{"type":"privacy","version":"1.0"},
                                             {"type":"sensitive","version":"1.0"},{"type":"age14","version":"1.0"}]}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("outdated_version"))
                .andExpect(jsonPath("$.error.details.type").value("terms"))
                .andExpect(jsonPath("$.error.details.current_version").value("1.0"));
    }

    @Test
    void 위기_연락처는_로그인_없이_나온다() throws Exception {
        mockMvc.perform(get("/public/crisis-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value("109"))
                .andExpect(jsonPath("$[1].number").value("1577-0199"))
                .andExpect(jsonPath("$[0].description").isNotEmpty());
    }
}
