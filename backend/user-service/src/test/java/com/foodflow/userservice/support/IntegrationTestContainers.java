package com.foodflow.userservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

/**
 * Real infrastructure for integration tests, same major versions as infra/docker/docker-compose.yml.
 * Containers are Spring beans, so they are started once per cached application context and shared
 * by all integration test classes using it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestContainers {

    public static final String APICURIO_IMAGE = "apicurio/apicurio-registry:3.3.4";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.2");
    }

    @Bean
    GenericContainer<?> schemaRegistry() {
        return new GenericContainer<>(APICURIO_IMAGE)
                .withExposedPorts(8080)
                // Apicurio 3.x serves health on the separate Quarkus management port (9000); wait on the
                // REST API itself instead, which also proves the registry is ready to serve schemas.
                .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /** application.yaml reads the registry URL from SCHEMA_REGISTRY_URL. */
    @Bean
    DynamicPropertyRegistrar schemaRegistryProperties(GenericContainer<?> schemaRegistry) {
        return registry -> registry.add("SCHEMA_REGISTRY_URL", () -> "http://" + schemaRegistry.getHost() + ":"
                + schemaRegistry.getMappedPort(8080) + "/apis/registry/v3");
    }
}
