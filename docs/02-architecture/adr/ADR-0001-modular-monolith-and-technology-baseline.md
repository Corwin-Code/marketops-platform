# ADR-0001 — Modular Monolith and Technology Baseline

- Status: ACCEPTED
- Date: 2026-08-06
- Source: Baseline D-03 and Section 7

## Context

The first version must learn business boundaries rapidly while retaining transactional consistency, clear module boundaries, strong controls and a production-grade path. Premature microservices, Kafka and Kubernetes would add distributed failure and operational complexity without proven need.

## Decision

Use:

- Java 21 + Spring Boot;
- PostgreSQL + Flyway;
- React + TypeScript;
- one deployable Modular Monolith backend initially;
- PostgreSQL Task/Outbox tables and workers for asynchronous work;
- Docker for local and controlled deployment;
- S3-compatible object storage for large Raw payloads/reports;
- explicit packages/modules and architecture tests; modules do not directly access another module's Repository.

Exact framework/tool versions are selected and pinned in WP-P0-001 after current official verification.

## Consequences

- simpler initial deployment and transaction management;
- module boundaries must be enforced by code structure and automated tests;
- future extraction remains possible through Application Services, Domain Events and explicit Query Ports;
- no microservice split without a new ADR and demonstrated operational need.
