# Hapag-Lloyd ZenRows Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the injected Hapag-Lloyd tracking implementation with a Java ZenRows client that retrieves the B/L result list, selects only its first container, fetches its detail page, and maps its movement data into the existing snapshot.

**Architecture:** Keep `HapagLloydTrackingClient` and the adapter/domain model stable. Add an HTTP-based ZenRows client using Spring `RestClient` and Jsoup; make it the injected implementation, retaining the existing Chrome class outside the Spring bean path. Externalize the API key and request options; do not log keys, request URLs, or response bodies.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring `RestClient`, Jsoup, JUnit 5, Mockito.

**Spec:** Approved scope in conversation: use ZenRows for Hapag-Lloyd, take only the first container found for a B/L, fetch that container's details, and preserve the current ShipArrival single-container model. No real ZenRows calls during automated verification.

## Global Constraints

- Only the first B/L result container is processed; no schema, dashboard, or multi-container persistence changes.
- ZenRows API key is supplied through `ZENROWS_API_KEY`, never hard-coded or logged.
- Automated tests use mocked HTTP responses/fixtures and consume zero ZenRows credits.
- The existing tracking adapter contract and status/ETA semantics remain unchanged.

## Review Focus

- Missing ZenRows credentials must fail with a safe actionable error; test absent/blank key.
- HTTP errors and timeout must become `HapagLloydTrackingException` without including credentials or response data; test non-2xx response and timeout/error.
- Empty/malformed B/L result table must fail rather than return a success snapshot; test no containers.
- Multiple B/L results must request detail for only the first; test two rows and assert one detail request for row one.
- Missing/empty movement details must fail safely; test detail page with no usable movements.

---

### Task 1: ZenRows HTTP and HTML tracking client

**Files:**
- Create: `src/main/java/com/shiparrival/adapter/hapaglloyd/ZenRowsHapagLloydTrackingClient.java`
- Modify: `pom.xml` (add Jsoup)
- Modify: `src/main/resources/application.properties` (external ZenRows settings)
- Create: `src/test/java/com/shiparrival/adapter/hapaglloyd/ZenRowsHapagLloydTrackingClientTest.java`
- Test fixtures: `src/test/resources/fixtures/hapag-lloyd/zenrows-booking.html`, `zenrows-container.html`

**Interfaces:**
- Consumes: `HapagLloydTrackingClient.fetchTrackingSnapshot(String)` and existing `HapagLloydTrackingSnapshot`, `HapagLloydContainer`, `HapagLloydMovement` types.
- Produces: Spring-injected implementation `ZenRowsHapagLloydTrackingClient`; constructor-inject a `RestClient.Builder` and bind `hapag-lloyd.zenrows.api-key` plus timeout/configuration. Keep network endpoint at ZenRows Fetch API and use target URL as the encoded `url` query parameter with `mode=auto` (do not combine it with `js_render`/`premium_proxy`).

- [ ] **Step 1: Write failing tests** for first-container-only behavior (two booking rows, details fetched only for first), successful mapping from fixture HTML to snapshot/movements, missing API key, non-2xx response, and absent results/details.
- [ ] **Step 2: Run targeted tests** with `./mvnw -Dtest=ZenRowsHapagLloydTrackingClientTest test`; expected: compile/test failure because the client and behavior do not exist.
- [ ] **Step 3: Add Jsoup and implement** the HTTP client. Parse booking rows from the known results table; select row zero only. Request the matching container detail URL using the observed Hapag-Lloyd query shape `view=S8510&container=<encoded container number>`; parse `table.groupBorder` information and `table.hl-tbl` movements. Mark a movement actual only when its cells contain the existing portal's actual/strong marker. Construct a snapshot containing exactly one container.
- [ ] **Step 4: Run targeted tests**; expected: all client tests pass without live network access.
- [ ] **Step 5: Configure Spring selection** so the ZenRows client is the single injected `HapagLloydTrackingClient`; the legacy Chrome implementation must not create an ambiguous bean or be started by application tracking.
- [ ] **Step 6: Run adapter tests** using `./mvnw -Dtest=HapagLloydTrackingAdapterTest,ZenRowsHapagLloydTrackingClientTest test`; expected: existing adapter behavior remains green and new client tests pass.

### Task 2: Configuration and operational documentation

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `README.md` or the existing Hapag-Lloyd configuration section (locate current section before editing)
- Test: `src/test/java/com/shiparrival/adapter/hapaglloyd/ZenRowsHapagLloydTrackingClientTest.java`

**Interfaces:**
- Consumes: Task 1's property names and client behavior.
- Produces: documented secret setup and deterministic config defaults; no credential values.

- [ ] **Step 1: Add a config test** proving the client reads an externally supplied key and that no key is required to instantiate the unrelated application context (unless Hapag tracking is invoked).
- [ ] **Step 2: Run the config test** and verify expected failure before adding the config behavior.
- [ ] **Step 3: Document** `ZENROWS_API_KEY`, safe local/deployment secret configuration, that each B/L performs one listing request plus one first-container detail request, and that actual credits depend on ZenRows' response header/configuration. Do not promise a fixed credit count.
- [ ] **Step 4: Run full suite** with `./mvnw test`; expected: zero failures/errors and the same three browser-only skips, with no ZenRows network requests.
- [ ] **Step 5: Run `git diff --check`**; expected: no whitespace errors.
