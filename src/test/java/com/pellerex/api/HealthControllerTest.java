package com.pellerex.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pellerex.api.config.AppProperties;
import com.pellerex.api.controller.HealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** J2 — the three platform health routes answer 200 at root (no /api, no version prefix). */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        AppProperties props = new AppProperties();
        props.setAllowedOrigins("https://www.pellerex.com");
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(props)).build();
    }

    @Test
    void startupLiveReadyReturn200() throws Exception {
        for (String path : new String[] {"/health/startup", "/health/live", "/health/ready"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }
}
