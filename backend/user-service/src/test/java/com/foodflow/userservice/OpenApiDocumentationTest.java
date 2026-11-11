package com.foodflow.userservice;

import com.foodflow.userservice.auth.api.AuthWebConfiguration;
import com.foodflow.userservice.auth.application.AuthService;
import com.foodflow.userservice.auth.application.PublicKeySetProvider;
import com.foodflow.userservice.common.config.OpenApiConfiguration;
import com.foodflow.userservice.common.config.SecurityConfiguration;
import com.foodflow.userservice.support.WebSliceTestSupport;
import com.foodflow.userservice.user.application.AddressService;
import com.foodflow.userservice.user.application.AdminUserService;
import com.foodflow.userservice.user.application.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generates the OpenAPI 3 contract from the real controllers (no database needed) and verifies the
 * essentials. The document is written to {@code target/openapi/user-service.json}; copy it to
 * {@code docs/api/user-service.openapi.json} whenever the API changes.
 */
@WebMvcTest
@Import({SecurityConfiguration.class, AuthWebConfiguration.class, OpenApiConfiguration.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
class OpenApiDocumentationTest extends WebSliceTestSupport {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private PublicKeySetProvider publicKeys;
    @MockitoBean
    private UserProfileService profiles;
    @MockitoBean
    private AddressService addresses;
    @MockitoBean
    private AdminUserService adminUsers;

    @Test
    void publishesTheUserServiceContract() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode api = JsonMapper.builder().build().readTree(json);
        assertThat(api.path("info").path("title").asString()).isEqualTo("FoodFlow User Service API");
        assertThat(api.path("paths").propertyNames()).contains(
                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/.well-known/jwks.json", "/api/v1/users/me", "/api/v1/users/me/addresses",
                "/api/v1/users/me/addresses/{addressId}", "/api/v1/admin/users", "/api/v1/admin/users/{userId}/status");
        assertThat(api.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asString())
                .isEqualTo("bearer");
        assertThat(api.path("paths").path("/api/v1/users/me").path("get").path("security").isArray()).isTrue();
        assertThat(api.path("components").path("schemas").path("RegisterRequest").path("required").toString())
                .contains("email", "password", "fullName");

        Path output = Path.of("target", "openapi", "user-service.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, api.toPrettyString());
    }
}
