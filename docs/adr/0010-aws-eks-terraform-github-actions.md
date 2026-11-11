# ADR-0010: AWS (EKS) deployment with Terraform, Helm and GitHub Actions (OIDC)

* Status: Accepted
* Date: 2026-11-07

## Decision
* **Compute:** Amazon EKS with one namespace per environment (dev, staging, prod), HPA and
  Karpenter.
* **Data:**
  * RDS for PostgreSQL (Multi-AZ; Payment on a dedicated instance).
  * MongoDB Atlas on AWS via PrivateLink. DocumentDB is not used because its MongoDB API
    compatibility is incomplete.
  * ElastiCache for Redis.
  * Amazon MSK, plus Apicurio Registry with persistent storage.
* **Edge:** CloudFront + WAF + ALB. The React apps are served from S3 + CloudFront.
* **Secrets:** AWS Secrets Manager + KMS, synced to Kubernetes via External Secrets Operator.
* **Images:** built with Cloud Native Buildpacks (`spring-boot:build-image`) and pushed to GHCR.
* **Infrastructure:** Terraform (`infra/terraform`, remote state). Workloads are deployed with a
  shared Helm chart plus per-service values.
* **CD:** GitHub Actions authenticates to AWS with **OIDC**, so there are no long-lived keys.
  * Dev deploys automatically.
  * Staging deploys on merge to main.
  * Prod deploys after manual approval, with canary releases for order and payment.
* Database migrations are expand/contract only, so N and N-1 versions of a service can run
  side by side.

## Consequences
* Managed services reduce the operational burden. The design remains portable to Azure (AKS,
  Azure Database for PostgreSQL, Azure Cache for Redis, Event Hubs/Confluent).
* Cost needs active governance: right-size dev, scale to zero overnight.
