package com.foodflow.platform.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Set;
import java.util.TreeSet;

/**
 * Fails startup when configuration references an undefined variable, e.g. {@code ${JWT_ISSUER}}.
 *
 * <p>Spring Boot's binder keeps unresolvable placeholders as literal text, so a missing secret or
 * URL would otherwise surface later as confusing runtime errors, or worse, as a valid-looking value
 * such as an issuer of {@code "${JWT_ISSUER}"}. Only application configuration is inspected; OS
 * environment variables and system properties are taken verbatim.
 *
 * <p><b>Runs as a {@link BeanFactoryPostProcessor}, before any bean is created.</b> An ordinary
 * singleton would run too late: Spring initializes {@code LoadTimeWeaverAware} beans (JPA's
 * {@code entityManagerFactory}, and with it Flyway and the DataSource) ahead of all other
 * singletons, so a missing database URL or password would surface as a JDBC error instead of
 * this guard's message.
 */
public class UnresolvedPlaceholderGuard implements BeanFactoryPostProcessor, PriorityOrdered {

    private static final Set<String> VERBATIM_SOURCES = Set.of(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

    private final ConfigurableEnvironment environment;

    public UnresolvedPlaceholderGuard(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        try {
            verify();
        } catch (IllegalStateException e) {
            throw new ApplicationContextException("Configuration guard rejected the application configuration", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * @throws IllegalStateException naming every property whose placeholders cannot be resolved
     */
    public void verify() {
        Set<String> unresolved = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (VERBATIM_SOURCES.contains(source.getName()) || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (enumerable.getProperty(name) instanceof CharSequence raw && raw.toString().contains("${")) {
                    try {
                        environment.resolveRequiredPlaceholders(raw.toString());
                    } catch (IllegalArgumentException e) {
                        unresolved.add(name + " = " + raw);
                    }
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Missing configuration. Define the referenced environment variables "
                    + "(or activate the 'local' profile for development): " + String.join(", ", unresolved));
        }
    }
}
