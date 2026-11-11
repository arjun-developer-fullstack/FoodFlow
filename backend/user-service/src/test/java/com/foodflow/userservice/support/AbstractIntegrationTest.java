package com.foodflow.userservice.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application (real security, JPA, Flyway, Kafka, Apicurio) against Testcontainers.
 *
 * <p><b>Requires Docker.</b> Without a Docker daemon every subclass is reported as <em>skipped</em>
 * ({@code disabledWithoutDocker = true}); CI runners always provide Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "correct horse battery staple";
    protected static final String REFRESH_COOKIE = "ff_refresh";

    @Autowired
    protected MockMvc mvc;

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected Session register(String email, String fullName) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"%s"}
                                """.formatted(email, PASSWORD, fullName)))
                .andExpect(status().isCreated())
                .andReturn();
        return Session.from(result);
    }

    protected Session login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return Session.from(result);
    }

    /** Access token and refresh-token cookie of one sign-in. */
    public record Session(String accessToken, String refreshToken) {

        public static Session from(MvcResult result) throws Exception {
            String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
            return new Session(accessToken, refreshCookieValue(result));
        }

        public String bearer() {
            return "Bearer " + accessToken;
        }

        public Cookie cookie() {
            return new Cookie(REFRESH_COOKIE, refreshToken);
        }
    }

    protected static String refreshCookieValue(MvcResult result) {
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return cookies.stream()
                .filter(header -> header.startsWith(REFRESH_COOKIE + "="))
                .map(header -> header.substring(REFRESH_COOKIE.length() + 1, header.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No refresh cookie in " + cookies));
    }
}
