# ADR-0009: Observability with Micrometer + OpenTelemetry and the Grafana stack

* Status: Accepted
* Date: 2026-11-07

## Decision
* **Metrics:** Micrometer, exported via OTLP to Prometheus/Mimir.
  * RED metrics per endpoint.
  * Kafka consumer lag and outbox backlog.
  * Business KPIs.
* **Traces:** OpenTelemetry (Boot-managed) to Tempo. `traceparent` is propagated over HTTP and in
  **Kafka headers**, so an order is traceable across the saga.
* **Logs:** Spring Boot structured JSON logging (ECS) to Loki, with `traceId`, `correlationId` and
  `userId`. PII is masked.
* **Health:** Actuator liveness/readiness probes.
* **Local:** the `grafana/otel-lgtm` all-in-one container. **Cloud:** the same stack (Grafana
  Cloud or self-hosted on EKS). Alertmanager routes alerts.
* SLOs: checkout p99 < 800 ms, availability 99.9%, saga completion > 99.5%.

## Consequences
* Vendor-neutral instrumentation (OTLP), so the backend can be swapped.
* Cardinality must be governed: no user/order ids as metric labels.
