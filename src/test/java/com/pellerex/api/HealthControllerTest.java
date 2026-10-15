package com.pellerex.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pellerex.api.controller.HealthController;
import com.pellerex.api.health.HealthCheck;
import com.pellerex.api.health.HealthTester;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** J2: the three platform health routes at root answer one word from the last health test. */
class HealthControllerTest {

    private static MockMvc serve(HealthTester tester) {
        return MockMvcBuilders.standaloneSetup(new HealthController(tester)).build();
    }

    private static void expect(MockMvc mockMvc, String path, int status, String word) throws Exception {
        mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(status))
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(word));
    }

    @Test
    void beforeTheFirstTestEveryAddressAnswersUnhealthy() throws Exception {
        MockMvc mockMvc = serve(new HealthTester(List.of()));

        for (String path : new String[] {"/health/startup", "/health/live", "/health/ready"}) {
            expect(mockMvc, path, 503, "Unhealthy");
        }
    }

    @Test
    void afterAPassedTestEveryAddressAnswersHealthy() throws Exception {
        HealthTester tester = new HealthTester(List.of());
        tester.test();
        MockMvc mockMvc = serve(tester);

        for (String path : new String[] {"/health/startup", "/health/live", "/health/ready"}) {
            expect(mockMvc, path, 200, "Healthy");
        }
    }

    /** A failed test keeps the app started, so Kubernetes does not kill it while it starts, and
     *  the answer is one word with no reason: anyone can call these addresses. */
    @Test
    void afterAFailedTestLiveAndReadyAnswerUnhealthyWithNoReason() throws Exception {
        HealthTester tester = new HealthTester(List.of(new HealthCheck() {
            @Override
            public String name() {
                return "database";
            }

            @Override
            public void test() {
                throw new IllegalStateException("Login failed for user orders-api");
            }
        }));
        tester.test();
        MockMvc mockMvc = serve(tester);

        expect(mockMvc, "/health/startup", 200, "Healthy");
        expect(mockMvc, "/health/live", 503, "Unhealthy");
        expect(mockMvc, "/health/ready", 503, "Unhealthy");
    }
}
