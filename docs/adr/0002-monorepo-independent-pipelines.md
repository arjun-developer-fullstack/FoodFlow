# ADR-0002: Monorepo with independent per-service pipelines

* Status: Accepted
* Date: 2026-11-07

## Context
The platform starts with a small team. Atomic changes across a contract and its consumers are
valuable, but services must still release independently.

## Decision
* A single GitHub repository rooted at `FoodFlow/`, organised as `backend/`, `frontend/`,
  `infra/` and `docs/`.
* `backend/foodflow-parent` centralises versions and build conventions. Each service inherits from
  it but has its **own version**, image, Helm release and pipeline.
* Every service has a thin GitHub Actions workflow with **path filters**. It calls
  `_reusable-java-service.yml` and builds only that service plus its dependencies
  (`-pl <svc> -am`).
* Shared code under `backend/libs` is limited to infrastructure concerns: the platform starter,
  event contracts and test support. **No shared domain models.**

## Consequences
* One place for version upgrades (Boot, Spring Cloud) and consistent tooling.
* A change to `foodflow-parent` triggers every service's pipeline, which is intentional.
* Need discipline (CODEOWNERS, ArchUnit) to prevent coupling through shared libs.
