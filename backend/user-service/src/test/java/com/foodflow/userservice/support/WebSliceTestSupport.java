package com.foodflow.userservice.support;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.UUID;

/**
 * Base for {@code @WebMvcTest} slices: real security configuration (imported by each test), no
 * database. The JWT decoder is mocked because requests carry pre-authenticated test JWTs.
 */
@ActiveProfiles("test")
public abstract class WebSliceTestSupport {

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    protected static JwtRequestPostProcessor userJwt(UUID userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(userId.toString()).claim("roles", Arrays.asList(roles)))
                .authorities(Arrays.stream(roles)
                        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
    }
}
