package com.foodflow.userservice.auth.api;

import com.foodflow.platform.web.CorrelationId;
import com.foodflow.userservice.auth.application.AuthCommands.AccountType;
import com.foodflow.userservice.auth.application.AuthCommands.AuthSession;
import com.foodflow.userservice.auth.application.AuthCommands.RegisterCommand;
import com.foodflow.userservice.auth.application.AuthService;
import com.foodflow.userservice.auth.domain.AuthExceptions.AccountLockedException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidCredentialsException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidRefreshTokenException;
import com.foodflow.userservice.common.config.SecurityConfiguration;
import com.foodflow.userservice.support.WebSliceTestSupport;
import com.foodflow.userservice.user.domain.UserExceptions.EmailAlreadyRegisteredException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, JwksController.class})
@Import({SecurityConfiguration.class, AuthWebConfiguration.class})
class AuthControllerTest extends WebSliceTestSupport {

    private static final String REFRESH_TOKEN = "dGhpcy1pcy1hLXRlc3QtcmVmcmVzaC10b2tlbi0wMTI";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private com.foodflow.userservice.auth.application.PublicKeySetProvider publicKeys;

    @Test
    void registerReturns201WithAccessTokenAndHardenedRefreshCookie() throws Exception {
        given(authService.register(any(), any())).willReturn(session());

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"jane@example.com","password":"correct horse battery staple","fullName":"Jane Doe"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/users/me"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("ff_refresh=" + REFRESH_TOKEN),
                        containsString("Path=/api/v1/auth"),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict"))))
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        ArgumentCaptor<RegisterCommand> command = ArgumentCaptor.forClass(RegisterCommand.class);
        verify(authService).register(command.capture(), any());
        assertThat(command.getValue().accountType()).isEqualTo(AccountType.CUSTOMER);
    }

    @Test
    void registerValidatesInputAndReportsEveryInvalidField() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"not-an-email","password":"short","fullName":" "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")));
        verify(authService, never()).register(any(), any());
    }

    @Test
    void registerCannotSelfAssignAdmin() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"x@example.com","password":"correct horse battery staple","fullName":"X","accountType":"ADMIN"}
                        """))
                .andExpect(status().isBadRequest());
        verify(authService, never()).register(any(), any());
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        given(authService.register(any(), any())).willThrow(new EmailAlreadyRegisteredException());

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"jane@example.com","password":"correct horse battery staple","fullName":"Jane"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void invalidCredentialsAre401AndLockedAccountsAre423() throws Exception {
        String body = """
                {"email":"jane@example.com","password":"wrong"}
                """;
        willThrow(new InvalidCredentialsException()).given(authService).login(any(), any());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        willThrow(new AccountLockedException(Duration.ofSeconds(899).plusMillis(400))).given(authService).login(any(), any());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900")) // rounded up, never too early
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void aBenignRefreshRaceInsideTheGraceWindowIs401WithoutClearingTheCookie() throws Exception {
        willThrow(InvalidRefreshTokenException.supersededWithinGrace()).given(authService).refresh(eq(REFRESH_TOKEN), any());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ff_refresh", REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void anyOtherRejectedRefreshClearsTheCookie() throws Exception {
        willThrow(new InvalidRefreshTokenException()).given(authService).refresh(eq(REFRESH_TOKEN), any());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ff_refresh", REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("ff_refresh="), containsString("Max-Age=0"))));
    }

    @Test
    void refreshReadsTheCookieAndRotatesIt() throws Exception {
        given(authService.refresh(eq(REFRESH_TOKEN), any())).willReturn(session());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ff_refresh", REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("ff_refresh=" + REFRESH_TOKEN)));
    }

    @Test
    void refreshWithoutCookieIs401AndClearsTheCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("ff_refresh="), containsString("Max-Age=0"))));
        verify(authService, never()).refresh(any(), any());
    }

    @Test
    void logoutRevokesAndClearsTheCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("ff_refresh", REFRESH_TOKEN)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        verify(authService).logout(REFRESH_TOKEN);
    }

    @Test
    void jwksIsPublic() throws Exception {
        given(publicKeys.publicJwkSet()).willReturn(java.util.Map.of("keys", java.util.List.of()));

        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=300")))
                .andExpect(jsonPath("$.keys").isArray());
    }

    @Test
    void unknownEndpointsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/internal/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static AuthSession session() {
        Instant now = Instant.now();
        return new AuthSession(UUID.randomUUID(), "header.payload.signature", now.plus(Duration.ofMinutes(15)),
                REFRESH_TOKEN, now.plus(Duration.ofDays(14)));
    }
}
