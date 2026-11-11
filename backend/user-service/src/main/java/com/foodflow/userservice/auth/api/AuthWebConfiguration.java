package com.foodflow.userservice.auth.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshTokenCookies.Settings.class)
public class AuthWebConfiguration {

    @Bean
    RefreshTokenCookies refreshTokenCookies(RefreshTokenCookies.Settings settings, Clock clock) {
        return new RefreshTokenCookies(settings, clock);
    }
}
