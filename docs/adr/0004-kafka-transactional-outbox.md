# ADR-0004: Kafka events via Transactional Outbox; idempotent at-least-once consumers

* Status: Accepted
* Date: 2026-11-07

## Context
State changes (order placed, payment authorized, …) must reliably reach other services.
Writing to the database and publishing to Kafka in two steps (dual write) loses or invents events
on partial failure.

## Decision
* **Apache Kafka 4.x (KRaft).** Topics are named `foodflow.<domain>.events.v1`, one per producing
  domain. The message key is the aggregate id.
* The producer writes the event to its own `outbox_events` table **in the same transaction** as
  the state change. A relay (in the platform starter) publishes pending rows with an idempotent
  producer (`acks=all`) and marks them published.
* Consumers are at-least-once and **idempotent**: each `eventId` is recorded in
  `processed_events` in the same transaction as the side effect.
* Failures go through `@RetryableTopic` with exponential backoff, then a `.dlt` topic. An admin
  tool replays DLT messages.
* Topic auto-creation is disabled on brokers. Topics are declared infrastructure.

## Consequences
* No lost or phantom events. Ordering is guaranteed per aggregate.
* Eventual consistency. The UI shows pending states, e.g. "awaiting restaurant".
* The outbox table needs housekeeping (purge published rows after N days).
