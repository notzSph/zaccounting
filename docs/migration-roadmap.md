# zAccounting migration roadmap

## Objective

Move the current full-stack Next.js application to a self-hosted application with a Next.js frontend and a Java/Spring Boot backend, backed by PostgreSQL. Keep one modular backend on the VPS. Next.js owns presentation; Spring Boot owns authentication, authorization, accounting rules, imports, reporting and database access.

This roadmap is a sequence of reviewable milestones. Work on a phase starts only after its entry conditions are clear, and each phase closes with its exit checks recorded. Status markers are maintained here and in the Discord tracking post.

## Target stack

- Frontend: TypeScript, Next.js and React
- Backend: Java, Spring Boot and Spring MVC, organized as a modular monolith
- Database: PostgreSQL, accessed with Spring Data JPA/Hibernate and explicit SQL where useful
- Migrations: Flyway
- Money: Java `BigDecimal` and PostgreSQL `NUMERIC`, with explicit precision and rounding rules
- Security: Spring Security, local accounts, secure cookie sessions, TOTP MFA and business-scoped permissions
- API: versioned REST API with OpenAPI documentation
- Imports and documents: Apache Commons CSV, PDFBox, Tesseract for scanned PDFs, private filesystem storage
- Jobs: persisted PostgreSQL job records and bounded Spring scheduling/execution
- Deployment: Docker Compose with Next.js, Spring Boot, PostgreSQL and Caddy
- Verification: JUnit, PostgreSQL Testcontainers and Playwright
- Operations: structured logs, restricted Actuator endpoints, PostgreSQL backups and WAL archiving

Pin mutually compatible supported versions and container images when the target projects are bootstrapped. Avoid managed cloud services, microservices and speculative infrastructure.

## Phases and status

Statuses: `Not started`, `In progress`, `Blocked`, `Complete`.

### 0. Inventory current behavior and data

**Status: Not started**

- Map existing Next.js screens, routes, API handlers, Prisma schema, auth, uploads, jobs and external dependencies.
- Record workflows and terminology to preserve, defects to correct, and behavior that is ambiguous.
- Map users, businesses, memberships, bank accounts, transactions, ledger data, audit history and documents.
- Identify legacy amounts/currencies, cascade-delete paths, and gaps in approval or audit history.
- Record unresolved policy choices, including self-approval, currency precision/rounding, FX treatment, period closing, and recovery targets.

**Exit checks:** feature inventory and data map reviewed; risks and unresolved decisions documented; no legacy data is changed.

### 1. Bootstrap the target application and local deployment

**Status: Not started**

- Create the target `apps/web/`, `apps/api/`, `infra/`, `scripts/` and `docs/` structure while preserving the existing app as the migration source.
- Pin Java, Spring Boot, Node, Next.js and PostgreSQL versions; add Maven Wrapper and reproducible frontend installs.
- Add local Docker Compose services for `apps/web`, `apps/api`, PostgreSQL and Caddy, with persistent data volumes and secrets outside source control.
- Configure Flyway as the schema owner; disable Hibernate schema mutation outside disposable tests.
- Build a minimal vertical slice: Next.js calls Spring Boot through the intended `/api/*` route, Spring Boot reads/writes a small non-financial record in PostgreSQL.
- Remove unnecessary runtime reliance on Vercel analytics or other hosted services as the frontend is moved.

**Exit checks:** clean checkout builds reproducibly; local stack starts; Caddy routes one public origin; Flyway initializes an empty PostgreSQL database; no database or management port is public.

### 2. Identity, businesses and access boundaries

**Status: Not started**

- Implement local user accounts, password handling, secure cookie sessions, CSRF protection, session rotation/revocation and rate limits.
- Implement TOTP MFA enrollment, confirmation, encrypted secrets, hashed one-use recovery codes and audited reset flows.
- Separate platform administration from business ownership. Model business-scoped owner/member memberships and explicit permissions.
- Enforce business membership on every request and related object lookup. Default deny and prevent removal of the last active owner.
- Decide and document the policy for whether a submitter may approve their own work. Until decided, deny self-approval.

**Exit checks:** authorization tests prove cross-business reads and writes are denied; MFA/session flows work; secrets do not appear in logs; the self-approval policy is recorded.

### 3. Ledger, approvals, currencies and audit

**Status: Not started**

- Define a complete chart of accounts and reporting mappings for assets, liabilities, equity, revenue and expenses.
- Implement draft → submitted → approved → posted journal lifecycle, including rejection/withdrawal behavior and business-scoped approvals.
- Enforce balanced postings, valid account ownership, open-period checks and atomic posting/audit updates.
- Make posted history immutable; correct posted entries through linked reversals and replacements.
- Define currency precision, rounding, exchange-rate conventions, residuals and realized/unrealized FX handling before implementation.
- Store original currency and amount, applied rate, rate date/source and functional-currency values. Use decimal strings in API payloads and decimal-safe UI logic.
- Keep append-only audit behavior in normal application operations, with actor, business, action, target, timestamp and correlation ID.

**Exit checks:** focused tests cover balance, precision, rounding, reversal, period closure, authorization, concurrency, rollback and audit; posting and audit changes commit atomically.

### 4. Reports and statements

**Status: Not started**

- Implement balance sheet, income statement and cash flow from posted ledger entries.
- Apply explicit business, period, currency and report-basis filters. Never sum unlike currencies or allow drafts/import candidates to affect statements.
- Validate totals against independently calculated fixtures and investigate discrepancies without silently altering legacy balances.

**Exit checks:** report totals reconcile to independently calculated fixtures; filtering and currency behavior are covered; unresolved differences are visible and documented.

### 5. Imports, files, background jobs and agent API

**Status: Not started**

- Build a shared ingest → parse → normalize → validate → review → approve/post pipeline. Imports create candidates, never posted journals directly.
- Add CSV mapping and locale handling, PDF text extraction and OCR for scanned documents, source provenance, duplicate review and validation feedback.
- Store private files under generated names outside public roots. Enforce size/type limits and authorized downloads.
- Add PostgreSQL-backed jobs with leases, retries, bounded backoff, restart recovery and idempotent effects.
- Add `/api/v1` DTO-based REST endpoints and OpenAPI docs. Agents use dedicated scoped, revocable service-account tokens, hashed at rest and shown once.
- Start agent scopes at read/import/draft. Approval and posting are separately granted. Human and agent requests share business rules and audit trails.
- Implement provider-specific bank adapters only when bank access, consent, and provider terms are confirmed. Begin with read-only transaction access.

**Exit checks:** import review and duplicate behavior are tested; job restart/retry and idempotency are tested; unauthorized business/file/token access is denied; no bank API availability is assumed.

### 6. Legacy data migration and reconciliation rehearsal

**Status: Not started**

- Design explicit, deterministic mappings for legacy users, businesses, memberships, transactions, ledger entries, versions, documents and audit records.
- Rehearse against a copy using resumable/idempotent migration steps. Preserve original evidence and quarantine ambiguous or invalid records.
- Produce reconciliation output for record counts, balances and statement totals. Do not fabricate missing approvals, currencies or history.
- Compare representative workflows in the old and new applications; resolve mapping exceptions before cutover planning.

**Exit checks:** rehearsal is repeatable; reconciliation results are reviewed; every excluded or quarantined record has a reason; backups and restoration have been exercised.

### 7. Cutover readiness and production transition

**Status: Not started**

- Document deployment, monitoring, backup/WAL retention, restore procedure, recovery targets and the single-VPS failure boundary.
- Prepare a cutover runbook with write freeze, final backup, migration, reconciliation, smoke checks and rollback limitations after new writes begin.
- Confirm operational secrets, SMTP route if needed, disk capacity, access restrictions and support ownership.
- Execute production cutover only after the owner explicitly authorizes the destructive/production action.

**Exit checks:** runbook is rehearsed; production data reconciliation is accepted; restore and rollback limits are understood; explicit cutover approval is recorded.

## Cross-cutting rules

- Keep this a modular monolith. Do not introduce Kubernetes, queues, Redis, microservices or managed dependencies without a demonstrated need and an explicit decision.
- Do not build a second accounting backend in Next.js, connect the frontend directly to PostgreSQL, or retain Prisma as a competing target persistence layer.
- Use `BigDecimal` and sized `NUMERIC` for all money. Never use binary floating point for financial arithmetic.
- Never cascade-delete posted accounting history. Archive referenced businesses/accounts and correct entries with reversals.
- Keep core bookkeeping usable without bank connectivity, external AI, analytics or hosted identity.
- Do not run blind schema conversion, dual-write by default, or destructive production migrations without explicit authorization.

## Progress log

Update this section and the Discord roadmap post when a milestone changes status. Record date, milestone, outcome, validation and blockers. Keep implementation detail in the linked project docs or pull requests.

| Date | Milestone | Update |
| --- | --- | --- |
| 2026-11-27 | Roadmap created | Target stack and phase sequence recorded. All phases not started. |
