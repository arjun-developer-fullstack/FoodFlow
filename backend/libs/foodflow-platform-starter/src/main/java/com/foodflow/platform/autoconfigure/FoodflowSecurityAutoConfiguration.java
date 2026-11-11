package com.foodflow.platform.autoconfigure;

import com.foodflow.platform.error.ProblemDetails;
import com.foodflow.platform.security.FoodflowJwtProperties;
import com.foodflow.platform.security.JwtValidation;
import com.foodflow.platform.security.ProblemDetailSecurityHandlers;
import com.foodflow.platform.security.RolesClaimAuthoritiesConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import tools.jackson.databind.json.JsonMapper;

/**
 * JWT resource-server building blocks. Each service still declares its own
 * {@code SecurityFilterChain} (its URL rules are its business), wiring in these beans:
 * <ul>
 *   <li>{@link JwtAuthenticationConverter}: {@code roles} claim to {@code ROLE_*}, principal = {@code sub}</li>
 *   <li>{@link ProblemDetailSecurityHandlers}: RFC 9457 401/403 responses</li>
 *   <li>{@link JwtDecoder}: RS256 via {@code foodflow.security.jwt.jwk-set-uri} with issuer and
 *       audience validation (skipped when the service defines its own decoder, e.g. the issuer)</li>
 * </ul>
 */
@AutoConfiguration(
        after = {FoodflowWebAutoConfiguration.class, JacksonAutoConfiguration.class},
        before = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(JwtDecoder.class)
@EnableConfigurationProperties(FoodflowJwtProperties.class)
public class FoodflowSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter foodflowJwtAuthenticationConverter(FoodflowJwtProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RolesClaimAuthoritiesConverter(properties.rolesClaim()));
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailSecurityHandlers foodflowSecurityHandlers(ProblemDetails problems, JsonMapper jsonMapper) {
        return new ProblemDetailSecurityHandlers(problems, jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "foodflow.security.jwt", name = "jwk-set-uri")
    public JwtDecoder foodflowJwtDecoder(FoodflowJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidation.forPlatform(properties.issuer(), properties.audience()));
        return decoder;
    }
}
