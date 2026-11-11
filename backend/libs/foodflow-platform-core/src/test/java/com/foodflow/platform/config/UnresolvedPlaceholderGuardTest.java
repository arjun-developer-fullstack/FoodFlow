package com.foodflow.platform.config;

import com.foodflow.platform.autoconfigure.FoodflowConfigGuardAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedPlaceholderGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodflowConfigGuardAutoConfiguration.class));

    @Test
    void failsStartupAndNamesThePropertyWhenAVariableIsUndefined() {
        runner.withPropertyValues("foodflow.security.jwt.issuer=${FOODFLOW_TEST_UNDEFINED_VARIABLE}")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("foodflow.security.jwt.issuer")
                        .hasMessageContaining("FOODFLOW_TEST_UNDEFINED_VARIABLE"));
    }

    @Test
    void acceptsResolvablePlaceholdersAndDefaults() {
        runner.withPropertyValues(
                        "base.url=https://example.test",
                        "client.url=${base.url}/api",
                        "pool.size=${FOODFLOW_TEST_UNDEFINED_VARIABLE:10}")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Regression: as an ordinary singleton the guard ran after JPA's LoadTimeWeaverAware
     * entityManagerFactory, so a missing database URL surfaced as a JDBC error instead.
     */
    @Test
    void failsBeforeAnyApplicationBeanIsCreated() {
        AtomicBoolean created = new AtomicBoolean();
        runner.withPropertyValues("spring.datasource.url=${FOODFLOW_TEST_UNDEFINED_VARIABLE}")
                .withBean("earlyBean", Object.class, () -> {
                    created.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).getFailure().rootCause().hasMessageContaining("spring.datasource.url");
                    assertThat(created).as("no bean may be instantiated before the guard has run").isFalse();
                });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("foodflow.config-guard.enabled=false", "x=${FOODFLOW_TEST_UNDEFINED_VARIABLE}")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
