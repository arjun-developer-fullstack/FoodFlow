package com.foodflow.platform.autoconfigure;

import com.foodflow.platform.config.UnresolvedPlaceholderGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Registers {@link UnresolvedPlaceholderGuard} (disable with {@code foodflow.config-guard.enabled=false}). */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanProperty(name = "foodflow.config-guard.enabled", matchIfMissing = true)
public class FoodflowConfigGuardAutoConfiguration {

    /** {@code static}: the guard is a BeanFactoryPostProcessor and must not force early creation of this class. */
    @Bean
    static UnresolvedPlaceholderGuard foodflowUnresolvedPlaceholderGuard(ConfigurableEnvironment environment) {
        return new UnresolvedPlaceholderGuard(environment);
    }
}
