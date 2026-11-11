package com.foodflow.platform.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps the JWT roles claim (e.g. {@code ["CUSTOMER"]}) to Spring authorities ({@code ROLE_CUSTOMER}),
 * so endpoints can use {@code hasRole("CUSTOMER")}. Malformed role names are ignored.
 */
public class RolesClaimAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Pattern ROLE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final String rolesClaim;

    public RolesClaimAuthoritiesConverter(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(rolesClaim);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .filter(role -> ROLE_NAME.matcher(role).matches())
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
