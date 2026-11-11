# ADR-0005: Avro event schemas with Apicurio Schema Registry

* Status: Accepted
* Date: 2026-11-07

## Context
Events are long-lived contracts between independently deployed services. Schema drift must be
caught before deployment, not in production consumers.

## Decision
* Event payloads are **Avro**. Schemas (`.avsc`) live in `backend/libs/foodflow-event-contracts`,
  which generates Java classes and is published as a versioned artifact.
* **Apicurio Registry 3.x** (`apicurio-registry-avro-serde-kafka` 3.3.4) enforces **BACKWARD**
  compatibility per topic. CI checks compatibility before merge.
* Every event uses a common envelope: `eventId`, `eventType`, `source`, `occurredAt`,
  `traceparent`, `schemaVersion`. A breaking change requires a new topic version (`.v2`).

## Consequences
* Compact, typed and evolvable messages, with compatibility checked automatically.
* Messages are harder to inspect by hand than JSON (mitigated by Kafka UI with registry integration).
* The registry is a runtime dependency. Producers cache schemas, and the cloud deployment runs it
  with persistent storage.
