# ADR-0003: Platform baseline — Java 21, Spring Boot 4.1, Spring Cloud 2025.1 (gateway only)

* Status: Accepted
* Date: 2026-11-07

## Context
The existing `user-service` was generated with Spring Boot 4.1.2. Boot 4 is a new generation:
* Spring Framework 7 and Spring Security 7
* Jakarta EE 11 and Hibernate 7
* Jackson 3 (`tools.jackson.*`)
* Modular starters
* JUnit 6 and Testcontainers 2

Compatibility was verified on 2026-11-07:
* start.spring.io maps **Spring Cloud 2025.1.4 → Spring Boot ≥4.0.0 and <4.2.0-M1**.
  Spring Cloud 2026.0 (for Boot 4.2) is still at RC stage.
* springdoc-openapi 3.2.0 is built against Boot 4.1.2.
* `resilience4j-spring-boot4` 2.4.0 exists.
* Testcontainers 2.0.5 is Boot-managed.

## Decision
* **Java 21 LTS** (Microsoft OpenJDK). Virtual threads are enabled for servlet services.
* **Spring Boot 4.1.2**, with **Spring Cloud 2025.1.4** used **only** by the API Gateway.
* No Eureka and no Config Server: Kubernetes DNS handles discovery, and ConfigMaps/Secrets
  handle configuration.
* All third-party versions are pinned in `foodflow-parent`. Boot-managed versions are never
  overridden without an ADR.
* Prefer Java `record`s for DTOs and events. Lombok is allowed for JPA entities.

## Consequences
* Long support window and modern Spring APIs (API versioning, HTTP interface clients,
  built-in resilience annotations).
* Libraries still on Jackson 2 / Boot 3 must be checked before adoption.
* Upgrading to Boot 4.2 requires moving Spring Cloud to 2026.0 GA in the same change.
