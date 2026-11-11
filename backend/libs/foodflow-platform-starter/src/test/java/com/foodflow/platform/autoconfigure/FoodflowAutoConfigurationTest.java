package com.foodflow.platform.autoconfigure;

import com.foodflow.platform.error.GlobalExceptionHandler;
import com.foodflow.platform.outbox.OutboxRelay;
import com.foodflow.platform.outbox.OutboxWriter;
import com.foodflow.platform.security.ProblemDetailSecurityHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class FoodflowAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    FoodflowWebAutoConfiguration.class,
                    FoodflowSecurityAutoConfiguration.class,
                    FoodflowOutboxAutoConfiguration.class))
            .withPropertyValues(
                    "foodflow.security.jwt.issuer=https://auth.foodflow.test",
                    "foodflow.security.jwt.audience=foodflow-api");

    @Test
    void registersWebAndSecurityBuildingBlocks() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(GlobalExceptionHandler.class)
                .hasSingleBean(ProblemDetailSecurityHandlers.class)
                .hasSingleBean(JwtAuthenticationConverter.class)
                .hasSingleBean(Clock.class));
    }

    @Test
    void createsJwksDecoderOnlyWhenJwkSetUriIsConfigured() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JwtDecoder.class));

        runner.withPropertyValues("foodflow.security.jwt.jwk-set-uri=http://user-service/.well-known/jwks.json")
                .run(context -> assertThat(context).hasSingleBean(JwtDecoder.class));
    }

    @Test
    void failsFastWhenIssuerOrAudienceIsMissing() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        FoodflowWebAutoConfiguration.class, FoodflowSecurityAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void outboxIsOptIn() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(OutboxWriter.class)
                .doesNotHaveBean(OutboxRelay.class));
    }
}
