package com.foodflow.platform.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.foodflow.platform.web.CorrelationId;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(new ProblemDetails("https://problems.test/")))
                .build();
    }

    @Test
    void mapsDomainExceptionToProblemDetailWithCodeAndCategoryStatus() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "corr-12345678");
        try {
            mvc.perform(get("/conflict"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://problems.test/email-taken"))
                    .andExpect(jsonPath("$.title").value("Conflict"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.detail").value("E-mail already registered"))
                    .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                    .andExpect(jsonPath("$.correlationId").value("corr-12345678"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void addsRetryAfterInWholeSecondsRoundedUpWhenTheExceptionKnowsTheDelay() throws Exception {
        mvc.perform(get("/locked"))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("LOCKED"));
        mvc.perform(get("/conflict")).andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void retryAfterIsNeverZeroOrNegative() {
        assertThat(GlobalExceptionHandler.retryAfterSeconds(Duration.ZERO))
                .isEqualTo(1);
        assertThat(GlobalExceptionHandler.retryAfterSeconds(Duration.ofMillis(1)))
                .isEqualTo(1);
        assertThat(GlobalExceptionHandler.retryAfterSeconds(Duration.ofSeconds(900)))
                .isEqualTo(900);
    }

    @Test
    void reportsEachInvalidFieldForBeanValidationFailures() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("name"));
    }

    @Test
    void rejectsMalformedJsonWithProblemDetail() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", containsString("unexpected error")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-db-password"))));
    }

    @Test
    void mapsMethodSecurityDenialTo403() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void mapsEveryCategoryToADistinctStatus() {
        java.util.Set<Integer> statuses = new java.util.HashSet<>();
        for (ErrorCategory category : ErrorCategory.values()) {
            int status = ProblemDetails.statusFor(category).value();
            assertThat(status).as(category.name())
                    .satisfiesAnyOf(s -> assertThat(s).isBetween(400, 499),
                            s -> assertThat(s).isEqualTo(503));
            statuses.add(status);
        }
        assertThat(statuses).hasSize(ErrorCategory.values().length);
    }

    @Test
    void temporarilyUnavailableIsA503() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BUSY"));
    }

    @RestController
    static class TestController {

        @GetMapping("/conflict")
        void conflict() {
            throw new DomainException(ErrorCategory.CONFLICT, "EMAIL_TAKEN", "E-mail already registered") {
            };
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("connection to secret-db-password failed");
        }

        @GetMapping("/busy")
        void busy() {
            throw new DomainException(ErrorCategory.UNAVAILABLE, "BUSY", "Try again shortly") {
            };
        }

        @GetMapping("/locked")
        void locked() {
            throw new DomainException(ErrorCategory.LOCKED, "LOCKED", "Locked for a while",
                    Duration.ofSeconds(89).plusMillis(1)) {
            };
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }
    }

    record Payload(@Email String email, @NotBlank String name) {
    }
}
