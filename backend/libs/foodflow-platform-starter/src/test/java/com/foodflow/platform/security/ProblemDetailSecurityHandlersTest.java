package com.foodflow.platform.security;

import com.foodflow.platform.error.ProblemDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailSecurityHandlersTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ProblemDetailSecurityHandlers handlers =
            new ProblemDetailSecurityHandlers(new ProblemDetails("https://problems.test/"), json);

    @Test
    void unauthenticatedRequestGets401ProblemAndBearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.authenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/users/me");
    }

    @Test
    void invalidTokenDetailIsGenericAndDoesNotEchoValidationErrors() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.authenticationEntryPoint().commence(new MockHttpServletRequest(), response,
                new InvalidBearerTokenException("Jwt expired at 2026-01-01 (internal detail)"));

        assertThat(response.getContentAsString())
                .contains("invalid or has expired")
                .doesNotContain("internal detail");
    }

    @Test
    void forbiddenRequestGets403Problem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.accessDeniedHandler().handle(new MockHttpServletRequest(), response, new AccessDeniedException("x"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("ACCESS_DENIED");
    }
}
