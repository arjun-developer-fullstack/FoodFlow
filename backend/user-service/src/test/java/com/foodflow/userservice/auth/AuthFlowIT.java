package com.foodflow.userservice.auth;

import com.foodflow.userservice.auth.application.OpaqueTokens;
import com.foodflow.userservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end authentication against PostgreSQL with real RS256 tokens and Argon2 hashes. */
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registerSignInUseTokenRefreshAndSignOut() throws Exception {
        String email = uniqueEmail("flow");
        Session registered = register(email, "Flow User");

        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, registered.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        String storedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(storedHash).startsWith("{argon2}$argon2id$");

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(registered.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        Session rotated = Session.from(refreshed);
        assertThat(rotated.refreshToken()).isNotEqualTo(registered.refreshToken());

        mvc.perform(post("/api/v1/auth/logout").cookie(rotated.cookie())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void replayingAConsumedRefreshTokenRevokesTheWholeSessionFamily() throws Exception {
        Session original = register(uniqueEmail("reuse"), "Reuse User");
        Session rotated = Session.from(mvc.perform(post("/api/v1/auth/refresh").cookie(original.cookie()))
                .andExpect(status().isOk()).andReturn());
        // Move the rotation outside the reuse grace window (10 s): the replay is then theft, not a benign race
        jdbc.update("UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL '1 minute' WHERE token_hash = ?",
                OpaqueTokens.hash(original.refreshToken()));

        mvc.perform(post("/api/v1/auth/refresh").cookie(original.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresLockTheAccountAndTheCounterIsPersisted() throws Exception {
        String email = uniqueEmail("lock");
        register(email, "Lock User");
        String wrong = """
                {"email":"%s","password":"definitely the wrong password"}
                """.formatted(email);

        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }
        Integer failures = jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?",
                Integer.class, email);
        assertThat(failures).as("failed attempts survive the failing transaction").isEqualTo(4);

        String lockingAttempt = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        String laterAttempt = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isLocked())
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        // Lock duration is 15 min: Retry-After counts down from 900 s and never exceeds it
        assertThat(Long.parseLong(lockingAttempt)).isBetween(1L, 900L).isGreaterThan(890L);
        assertThat(Long.parseLong(laterAttempt)).isBetween(1L, Long.parseLong(lockingAttempt));
    }

    @Test
    void aRetryInsideTheGraceWindowIsRejectedWithoutClearingTheCookieAndTheSessionSurvives() throws Exception {
        Session original = register(uniqueEmail("grace"), "Grace User");
        Session rotated = Session.from(mvc.perform(post("/api/v1/auth/refresh").cookie(original.cookie()))
                .andExpect(status().isOk()).andReturn());

        // e.g. the browser retried the request: the old cookie arrives again moments after the rotation
        mvc.perform(post("/api/v1/auth/refresh").cookie(original.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated.cookie()))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateRegistrationIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail("dup");
        register(email, "Dup User");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s","fullName":"Again"}
                        """.formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void forgedOrMalformedBearerTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        Session session = register(uniqueEmail("forged"), "Forged User");
        String[] parts = session.accessToken().split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }
}
