package com.foodflow.userservice.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 document metadata. Served at /v3/api-docs (internal), Swagger UI at /swagger-ui.html. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI userServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FoodFlow User Service API")
                        .version("v1")
                        .description("""
                                Identity, authentication and profiles.

                                * Access tokens are RS256 JWTs (15 min) verified via `/.well-known/jwks.json`.
                                * Refresh tokens are rotated on every use and only travel in an HttpOnly cookie.
                                * Errors are RFC 9457 problem details with a stable `code` and a `correlationId`.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
