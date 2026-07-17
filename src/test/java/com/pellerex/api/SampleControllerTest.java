package com.pellerex.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pellerex.api.config.AppSecrets;
import com.pellerex.api.controller.SampleController;
import com.pellerex.api.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** J1/J15 — the sample endpoint reports secret presence (never the value), and @Valid + the
 *  @RestControllerAdvice turn bad input into a clean 400. */
class SampleControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        AppSecrets secrets = new AppSecrets("Server=db;Database=app;User Id=sa;");
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController(secrets))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void helloReportsSecretConfiguredWithoutLeakingIt() throws Exception {
        mockMvc.perform(get("/v1/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(110))
                .andExpect(jsonPath("$.dbConnectionStringConfigured").value(true));
    }

    @Test
    void echoRejectsBlankMessageWith400() throws Exception {
        mockMvc.perform(post("/v1/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
