package com.mindcare.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 프론트가 OpenAPI로 타입을 만들기 때문에(TRD 7-5) 문서와 실제 응답의 필드 이름이 같아야 한다.
 */
@SpringBootTest
class OpenApiContractTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void OpenAPI_필드는_실제_응답처럼_snake_case다() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.MeResponse.properties.suspended_until").exists())
                .andExpect(jsonPath("$.components.schemas.MeResponse.properties.ethics_training_required").exists())
                .andExpect(jsonPath("$.components.schemas.MeResponse.properties.suspendedUntil").doesNotExist());
    }
}
