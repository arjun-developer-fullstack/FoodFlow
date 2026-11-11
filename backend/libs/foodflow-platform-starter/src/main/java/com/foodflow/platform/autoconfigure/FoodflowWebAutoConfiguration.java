package com.foodflow.platform.autoconfigure;

import com.foodflow.platform.error.GlobalExceptionHandler;
import com.foodflow.platform.error.ProblemDetails;
import com.foodflow.platform.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Web conventions for servlet services: problem-detail error handling, correlation ids and a UTC
 * {@link Clock} (inject it instead of calling {@code Instant.now()} so time is testable).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FoodflowWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetails foodflowProblemDetails(
            @Value("${foodflow.problems.type-base-uri:https://problems.foodflow.com/}") String typeBaseUri) {
        return new ProblemDetails(typeBaseUri);
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler foodflowGlobalExceptionHandler(ProblemDetails problems) {
        return new GlobalExceptionHandler(problems);
    }

    /** Runs before Spring Security so even 401 responses carry the correlation id. */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> foodflowCorrelationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock foodflowClock() {
        return Clock.systemUTC();
    }
}
