package com.mindcare.domain.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class HealthControllerTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void DB와_Redis가_살아있으면_ok() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.db").value("ok"))
                .andExpect(jsonPath("$.redis").value("ok"));
    }
}
