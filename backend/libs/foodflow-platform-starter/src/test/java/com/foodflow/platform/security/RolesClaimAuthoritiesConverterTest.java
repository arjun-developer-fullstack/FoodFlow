package com.foodflow.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RolesClaimAuthoritiesConverterTest {

    private final RolesClaimAuthoritiesConverter converter = new RolesClaimAuthoritiesConverter("roles");

    @Test
    void mapsRolesToPrefixedAuthorities() {
        Jwt jwt = jwtWithRoles(List.of("CUSTOMER", "restaurant_owner", "CUSTOMER"));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER", "ROLE_RESTAURANT_OWNER");
    }

    @Test
    void ignoresMalformedRoleNames() {
        Jwt jwt = jwtWithRoles(List.of("ADMIN", "ROLE ADMIN; DROP", "", "1BAD"));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void returnsNoAuthoritiesWhenClaimIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRoles(List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").claim("roles", roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
