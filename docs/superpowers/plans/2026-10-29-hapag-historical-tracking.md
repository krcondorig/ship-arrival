# Hapag-Lloyd Historical Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent operator-assisted Hapag-Lloyd historical tracking workflow that stores validated per-container events and never automates Cloudflare/CAPTCHA.

**Architecture:** First deliver a manual Hyperbrowser/CDP spike and freeze an anonymized HTML contract fixture. Only if a trusted process can read the same DOM after the human interaction, add a persisted job/event model, provider adapter, protected operator endpoints/view, and a trusted extraction worker. Spring Boot remains the authority for authorization, validation, persistence and state transitions; GitHub Actions remains only the existing scheduled monitoring/health-check workflow.

**Tech Stack:** Java 21, Spring Boot 3.4.2, JPA/JDBC, Supabase PostgreSQL, Thymeleaf, Hyperbrowser Sessions API, Playwright/CDP worker, Maven Wrapper, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-29-hapag-historical-tracking-design.md`

## Global Constraints

- Never automate, solve, or evade CAPTCHA/Cloudflare; the operator performs any challenge manually.
- Do not enable Hyperbrowser `solveCaptchas`, stealth, proxy evasions, or CAPTCHA evaluation APIs.
- Provider API key and Supabase secrets stay in Render/worker environment secrets; never send them to browser JS.
- Treat Hyperbrowser `liveUrl` as a bearer secret; do not persist/log it and only return it to an authorized operator with `Cache-Control: no-store`.
- Do not use GitHub Actions to operate interactive browser sessions.
- Mark a job `COMPLETED` only after at least one real event row is validated and committed.
- Stop sessions on every terminal path; bound session/job duration and concurrency.
- Preserve existing shipment tracking and dashboard/auth patterns; do not replace them without an explicit reason.
- Keep historical events separate from current Shipment ETA/status/alerts in the initial release.

## Review Focus

- CDP worker is attached to the same browser state the operator sees after manual action: test in the provider spike; without it, stop before full integration.
- No valid events or malformed dates: parser/service tests prove the job cannot become `COMPLETED`.
- Duplicate HTTP/job delivery: integration test proves event/job idempotency.
- Live URL/API key leakage and unauthorized access: controller/security tests prove no secret goes to an unauthorized user or cache/log response.
- Worker crash, provider timeout, cancellation and restart: lifecycle tests prove bounded expiry, cleanup and truthful job status.

---

## File Structure

**Spike artifacts**
- Create `tools/hapag-hyperbrowser-spike/README.md` — operator-run procedure and exact manual safety checklist.
- Create `tools/hapag-hyperbrowser-spike/` runner source/config — isolated Playwright/CDP experiment; no secrets committed and no production integration.
- Create `src/test/resources/fixtures/hapag/history-multi-container.html` — anonymized HTML captured only after a manually completed, authorized test.
- Create parser contract tests under `src/test/java/com/shiparrival/adapter/hapaglloyd/history/`.

**Production application (only after spike gate passes)**
- Create `src/main/java/com/shiparrival/domain/HapagTrackingJob.java`, `HapagTrackingJobStatus.java`, `HapagTrackingEvent.java`.
- Create corresponding `repository/HapagTrackingJobRepository.java`, `HapagTrackingEventRepository.java`.
- Create DTOs under `service/dto/hapag/` for job request/status, event payload, provider session and extraction result.
- Create `adapter/hapaglloyd/history/HyperbrowserSessionClient.java` and provider response DTOs; it owns provider HTTP calls only.
- Create `adapter/hapaglloyd/history/HapagHistoryExtractor.java`; it connects to the remote CDP endpoint in the trusted worker and extracts only the event table after operator-ready signal. It must not interact with challenges.
- Create `service/HapagHistoricalTrackingService.java` for job transitions, limits, validation, idempotent event persistence and cleanup.
- Create `web/HapagHistoricalTrackingController.java` for auth-protected routes.
- Create `templates/hapag-tracking-job.html` for operator live-view and status/results.
- Create versioned SQL migration `supabase/migrations/20261029_hapag_tracking_history.sql`; do not rely only on Hibernate `ddl-auto=update` for uniqueness/FKs/indexes.
- Modify `application.properties` only for non-secret bounded defaults; secrets are environment variables.
- Modify dashboard only to add an operator action/link after job model and page exist; do not alter shipment status/alerts.

**Assumptions used by this plan (confirm at review):** use `FAILED` with error code `NO_VALID_EVENTS` instead of a new `NO_EVENTS` state; first UI is a historical job/results page linked from each Hapag shipment; initial rollout allows one active Hapag job globally, 10-minute provider session timeout and a 15-minute job deadline, with per-user duplicate requests rejected while active. The exact provider plan price and Render background-worker availability must be checked before selecting the production worker deployment; do not proceed past the spike until confirmed.

## Task 1: Hyperbrowser Same-Session DOM Spike (Gate)

**Files:**
- Create: `tools/hapag-hyperbrowser-spike/README.md`
- Create: `tools/hapag-hyperbrowser-spike/` runner (choose Node.js/TypeScript + official SDK only if supported by current docs; otherwise Playwright/CDP client with direct documented session API)
- Create after successful manual run: `src/test/resources/fixtures/hapag/history-multi-container.html`
- Create: `src/test/java/com/shiparrival/adapter/hapaglloyd/history/HapagHistoryHtmlContractTest.java`

**Interfaces:**
- Spike output is an anonymized HTML fixture and JSON extraction sample with fields `containerNumber`, `status`, `place`, `eventDate`, `eventTime`, `transport`, `voyageNo`.
- No production job/session API is created in this task.

- [ ] **Step 1: Record an operator-only run procedure.** Document required local env secrets (names only), creation with a short timeout, initial navigation, manual challenge handling only by operator, session cleanup, and redaction. Explicitly prohibit solver/stealth/proxy settings.
- [ ] **Step 2: Create the short-lived browser session using documented API and keep API key on the trusted runner.** Confirm response has `id`, `liveUrl`, `wsEndpoint`; do not print sensitive URLs/endpoints in logs.
- [ ] **Step 3: Attach a trusted Playwright/CDP process before or during operator interaction and inspect whether its page DOM changes after the operator uses Live View.** Do not click, type, or evaluate challenge-specific actions. If it is not the same page/state, stop this plan and return to design.
- [ ] **Step 4: After operator confirms tracking content is displayed, extract the visible historical rows from that page only.** Confirm at least two containers and the observed six fields against the Live View. Stop the session in `finally` and confirm provider status is closed.
- [ ] **Step 5: Save an anonymized fixture and hand-checked JSON sample.** Remove B/L, identifying container numbers where necessary, cookies, tokens, session URLs, user data and unrelated page content while preserving DOM structure and values needed for parsing.
- [ ] **Step 6: Write parser contract tests against the fixture.** Assert exact literal events for multiple containers; missing required date/status and empty rows are rejected; DOM shape change returns a controlled parse failure.
- [ ] **Step 7: Verify spike gate.** Run `mvnw.cmd -o -Dtest=HapagHistoryHtmlContractTest test` on Windows or `./mvnw -o -Dtest=HapagHistoryHtmlContractTest test` on Linux/macOS. Expected: tests pass and manual evidence confirms CDP sees operator-loaded DOM. If no verified provider run/fixture exists, do not begin Tasks 2–8.

## Task 2: Persisted Job and Event Model

**Files:**
- Create domain/repositories listed above.
- Create SQL migration in `supabase/migrations/`.
- Create tests under `src/test/java/com/shiparrival/repository/` and `service/`.

**Interfaces:**
- `HapagTrackingJobStatus`: `PENDING`, `SESSION_CREATED`, `WAITING_FOR_OPERATOR`, `EXTRACTING`, `COMPLETED`, `FAILED`, `EXPIRED`, `CANCELLED`.
- `HapagHistoricalTrackingService.createJob(Long shipmentId, Long userId, String idempotencyKey) -> HapagTrackingJob`.
- `HapagHistoricalTrackingService.acceptEvents(UUID jobId, List<HapagTrackingEventPayload> events) -> HapagTrackingJob`.
- `HapagTrackingEvent.eventKey` unique per job; stable key is derived from normalized container and event fields, not list position.

- [ ] **Step 1: Write failing persistence tests** for job creation against existing HAPAG_LLOYD shipment, rejection of non-Hapag shipment, same idempotency key returning same job, duplicate active job rejection, and event duplicate uniqueness.
- [ ] **Step 2: Run focused repository tests** and confirm failures reflect missing model/schema behavior.
- [ ] **Step 3: Add JPA entities/repositories and SQL migration.** Include FK/indexes, uniqueness constraints, timestamps, status varchar (not PostgreSQL enum), and deletion behavior that preserves audit/event records according to agreed retention.
- [ ] **Step 4: Implement minimum job creation and idempotency service.** Use transaction boundaries; do not call provider yet.
- [ ] **Step 5: Run PostgreSQL-compatible integration test** (Testcontainers if dependency available; otherwise local configured test PostgreSQL) plus H2 unit suite. Expected schema and uniqueness assertions pass.
- [ ] **Step 6: Commit** `feat: persist Hapag historical tracking jobs and events`.

## Task 3: Event Validation and Atomic Completion

**Files:**
- Create event input/response DTOs and `HapagHistoricalTrackingService` validation methods.
- Create parser and tests in `adapter/hapaglloyd/history/` using Task 1 fixture.

**Interfaces:**
- `HapagHistoryExtractor.extract(String html) -> List<HapagTrackingEventPayload>` (pure parsing boundary; actual HTML acquisition remains Task 4).
- Event required values: nonblank container, status, place, parseable event date and time; transport/voyage may be nullable only if fixture confirms they are sometimes absent.
- `acceptEvents` transactionally inserts valid unique events and transitions to `COMPLETED` iff inserted-or-already-persisted valid event count is greater than zero; otherwise transitions to `FAILED` with `NO_VALID_EVENTS`.

- [ ] **Step 1: Write failing fixture contract tests** for multiple containers, multiple events, whitespace/format normalization, missing required values, invalid date/time, empty list, and mismatched requested BL/job.
- [ ] **Step 2: Run parser tests and confirm RED.**
- [ ] **Step 3: Implement deterministic HTML parser using Jsoup only if dependency approval is obtained; otherwise use existing project dependencies and narrowly scoped DOM parser.** Add Jsoup only after reviewing security/version and project conventions.
- [ ] **Step 4: Write failing service tests** for `COMPLETED` only with validated rows, `FAILED/NO_VALID_EVENTS` with empty/malformed rows, atomic rollback on persistence exception, and duplicate deliveries not creating duplicate events.
- [ ] **Step 5: Implement transactionally validated `acceptEvents`.** Ensure stored event count is derived from persisted rows, not untrusted submitted count.
- [ ] **Step 6: Run tests** and confirm all conditions, then commit `feat: validate and persist Hapag historical events`.

## Task 4: Provider Session Client and Trusted Worker Extraction

**Files:**
- Create provider API client, DTOs and worker/extractor integration.
- Create `src/test/java/com/shiparrival/adapter/hapaglloyd/history/HyperbrowserSessionClientTest.java`.
- Modify `application.properties` for bounded timeout/concurrency defaults; secrets remain environment-only.

**Interfaces:**
- `HyperbrowserSessionClient.createSession(Duration timeout) -> ProviderSession` where `ProviderSession` includes id, live URL and CDP endpoint only in memory; do not include any challenge-solving/stealth options.
- `HyperbrowserSessionClient.getSession(String id) -> ProviderSessionStatus`.
- `HyperbrowserSessionClient.stopSession(String id) -> void` (idempotent).
- Worker consumes job/session reference and posts typed event DTOs to service; no end-user browser JS reads CDP endpoint/API key.

- [ ] **Step 1: Write mocked HTTP contract tests** using documented current API: create, get/refreshed live URL, stop; malformed response, 401/429/5xx, timeout and redacted logging. Explicitly assert request excludes `solveCaptchas`, stealth and proxy evasion parameters.
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Implement provider client** using Spring `RestClient`/`WebClient` already available in Spring Boot, with connection/read timeouts, bounded retry only for transient provider errors, and no key in exception text/logs.
- [ ] **Step 4: Write worker lifecycle tests** that attach CDP to a provider fake seam, wait for explicit operator-ready signal, extract fixture-equivalent rows without challenge interaction, and always stop sessions on complete/cancel/error.
- [ ] **Step 5: Implement trusted worker.** First inspect deployment environment. Use separate Render background worker if supported/approved; otherwise run a bounded managed executor inside the web app and persist enough state for reconciliation after restart. Do not create an unbounded `@Async` task or move this work to GitHub Actions.
- [ ] **Step 6: Run contract + lifecycle tests and commit** `feat: connect assisted Hapag jobs to remote browser sessions`.

## Task 5: Job State Machine, Expiration, Cleanup and Retry

**Files:**
- Modify `HapagHistoricalTrackingService` and job repository.
- Create `HapagTrackingJobLifecycleService` only if separate responsibility is needed; tests under service.

**Interfaces:**
- Allowed transitions: `PENDING -> SESSION_CREATED -> WAITING_FOR_OPERATOR -> EXTRACTING -> COMPLETED`; any nonterminal state may transition to `FAILED`, `EXPIRED`, or `CANCELLED`; terminal states do not transition back.
- Default provider timeout 10 minutes; job deadline 15 minutes; only one active job globally until measured capacity and account pricing justify raising it.

- [ ] **Step 1: Write failing transition tests** for allowed transitions, terminal-state immutability, expiry, cancellation and provider session stop invocation.
- [ ] **Step 2: Write failing recovery tests** for process restart with expired job and active orphan provider session; repeat cleanup is safe.
- [ ] **Step 3: Implement state transition guards and scheduled reconciliation** (poll provider session status only while nonterminal; stop orphan sessions; no DOM automation during recovery).
- [ ] **Step 4: Test bounded transient retries** for provider creation failures; ensure each created session is stopped before replacement.
- [ ] **Step 5: Run tests and commit** `feat: expire and recover abandoned Hapag tracking jobs`.

## Task 6: Authorized Operator API and Live View UI

**Files:**
- Create `web/HapagHistoricalTrackingController.java`, templates and DTOs.
- Modify dashboard/template only to add entry point for HAPAG_LLOYD rows.
- Tests in `src/test/java/com/shiparrival/web/`.

**Interfaces:**
- `POST /api/hapag/tracking-jobs` with `shipmentId` and `Idempotency-Key`.
- `GET /api/hapag/tracking-jobs/{id}` status/events.
- `POST /api/hapag/tracking-jobs/{id}/live-view` returns a fresh live URL to authorized operator only.
- `POST /api/hapag/tracking-jobs/{id}/cancel` cancels and closes session.
- Internal ingestion endpoint is not exposed as a browser route if worker is in same trusted process; if a separate worker requires HTTP, it is a separate authenticated internal route with service token.

- [ ] **Step 1: Write failing authorization tests**: ADMIN/OPERADOR allowed, USER denied for mutations/Live URL, unauthorized/unknown job returns 404/403 without leaking existence or provider metadata, CSRF behavior consistent with existing forms.
- [ ] **Step 2: Write failing response tests**: live URL is no-store, refreshed via provider API, not present in job DTO/list/dashboard or exception logs; API key and `wsEndpoint` never serialized.
- [ ] **Step 3: Implement controller routes using `AuthFilter.CURRENT_USER_ATTRIBUTE` and existing `requireOperator` semantics.** Apply bounds and idempotency validation.
- [ ] **Step 4: Add operator results page** showing job state, expiry countdown, open-live-view action, and historical events grouped by container; no browser-side provider key/CDP logic. Include Hapag action on shipment row only for operator/admin and Hapag shipment.
- [ ] **Step 5: Add rendering tests** for status, expiry, grouped rows, no events and failure; user role sees no mutating actions.
- [ ] **Step 6: Run all web tests and commit** `feat: expose protected Hapag history jobs to operators`.

## Task 7: PostgreSQL Rollout, Observability, Limits and Documentation

**Files:**
- Finalize `supabase/migrations/...` and rollout README/docs.
- Update `AGENTS.md` with actual verified architecture only.
- Configure Render env var names for provider and worker (no secret values in repository).

**Interfaces:**
- Environment variables: `HYPERBROWSER_API_KEY`, `HAPAG_HISTORY_ENABLED` (default false), `HAPAG_HISTORY_SESSION_TIMEOUT_MINUTES` (default 10), `HAPAG_HISTORY_JOB_TIMEOUT_MINUTES` (default 15), `HAPAG_HISTORY_MAX_ACTIVE_JOBS` (default 1), and worker auth secret only if separate process is selected.

- [ ] **Step 1: Verify SQL migration on PostgreSQL** including fresh schema, existing schema, unique constraints and rollback safety; do not run against production without deployment approval.
- [ ] **Step 2: Add metrics/log fields** for job ID, status transition, elapsed seconds, event count and provider result code only; redact all URLs, keys, cookies and raw DOM.
- [ ] **Step 3: Add feature flag and fail closed** when provider key is absent or disabled; UI reports feature unavailable without creating a job.
- [ ] **Step 4: Add operational documentation** for Render secrets, DB migration order, provider plan check, cleanup, manual operator runbook and disable/rollback procedure.
- [ ] **Step 5: Run full suite** `./mvnw -o test`; expected all tests pass with only the existing browser-integration skips.
- [ ] **Step 6: Deploy disabled, apply migration, perform supervised provider test, then enable only after event parity and cleanup/cost checks pass.**
- [ ] **Step 7: Commit** `docs: document Hapag assisted historical tracking operations`.

## Plan Self-Review

- Spec coverage: architecture, provider capabilities, secret boundaries, DB schema, job states, endpoints, extraction owner/return path, idempotency, retries, timeout, cleanup, costs, CI separation, tests and manual provider validation all have tasks.
- Gate: Task 1 is a hard stop. No production Tasks 2–7 before CDP sees the operator-loaded live DOM and yields a validated anonymized fixture.
- Type consistency: job/event DTOs and service signatures are stable across tasks; worker uses only service-level event payload, not direct DB access.
- Review risks: empty result, duplicates, auth/secret exposure, expiry/restarts, mismatched B/L and malformed dates are explicitly tested.
- Scope: this plan covers the full project; the execution is staged so a failed spike is a valid stopping point, not a reason to build a speculative worker.
