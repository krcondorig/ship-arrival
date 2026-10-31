# Carrier Network Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare real adapter tracking for ONE `TCLU2987000`, MSC `MSNU7790400`, and MAERSK `275792562` from GitHub Actions without Tailscale and through the phone exit node.

**Architecture:** Add an opt-in JUnit integration test which directly instantiates/invokes each carrier adapter, independent of Spring application startup, persistence, notifications, and scheduled monitoring. A manual GitHub Actions workflow runs the same test twice, first without Tailscale and then with the existing phone exit node, and prints only bounded success/failure and selected parsed fields.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5, existing carrier adapters/Selenium clients, GitHub Actions, Tailscale.

**Spec:** Approved scope in conversation: real tracking-data test for ONE `TCLU2987000`, MSC `MSNU7790400`, and MAERSK `275792562`; do not test Hapag-Lloyd because it uses ZenRows.

## Global Constraints

- Do not launch or change `.github/workflows/daily-monitoring.yml`.
- Do not connect to or modify the production database; do not send notifications.
- Do not test Hapag-Lloyd.
- Do not log API response bodies, HTML, cookies, tokens, or credentials.
- A result counts as working only when the real adapter returns success and parsed tracking data; HTTP page reachability alone is insufficient.
- Each network condition runs as an independent GitHub Actions job so Tailscale cannot leak between test cases.
- The test workflow must be manual-only and use the existing Tailscale secret only in the exit-node job.

## Review Focus

- Disabled test guard prevents accidental real carrier requests in the default suite; test that absent opt-in skips.
- A carrier failure must not hide results from other carriers; test/output each carrier independently.
- Adapter resource cleanup must run after both success and failure; ensure `finally` cleanup.
- Logs must not include response payloads or secrets; assert output formatting only uses success and selected fields.
- A partial result without vessel/ETA may still be a real response; define success as adapter success plus at least one identifying/data field and report field availability rather than impose unsupported completeness.

---

### Task 1: Add isolated real-adapter integration diagnostic

**Files:**
- Create: `src/test/java/com/shiparrival/CarrierNetworkDiagnosticTest.java`

**Interfaces:**
- Consumes: Existing `OneLineTrackingAdapter`, `MscTrackingAdapter`, `MaerskTrackingAdapter`, `TrackingResult`.
- Produces: Opt-in test `CarrierNetworkDiagnosticTest` controlled by `CARRIER_NETWORK_DIAGNOSTIC=true`; each carrier runs independently and reports carrier name, success, status, and availability/value of selected parsed fields only.

- [ ] **Step 1: Write the integration test guarded by `@EnabledIfEnvironmentVariable`**

Add three opt-in test methods, one per carrier, each using exactly its approved guide. Each asserts `result.isSuccess()` and at least one nonblank identifier/status field, printing a sanitized one-line summary without raw result/error payload. Construct browser clients as existing test patterns do. Always close each client in `finally`.

- [ ] **Step 2: Verify the test is skipped without opt-in**

Run: `./mvnw.cmd -Dtest=CarrierNetworkDiagnosticTest test`
Expected: one skipped test, no network calls.

- [ ] **Step 3: Review the test's input and output boundaries**

Confirm only the three fixed tracking numbers are sent and output fields are allow-listed; no body, cookies, headers, API keys, or arbitrary exception text are printed.

- [ ] **Step 4: Run targeted static compilation/test**

Run: `./mvnw.cmd -Dtest=CarrierNetworkDiagnosticTest test`
Expected: BUILD SUCCESS with the integration test skipped absent the opt-in flag.

### Task 2: Add manual workflow with isolated network-condition jobs

**Files:**
- Create: `.github/workflows/carrier-network-diagnostic.yml`
- Modify: `src/test/java/com/shiparrival/CarrierNetworkDiagnosticTest.java` only if environment wiring requires a named helper.

**Interfaces:**
- Consumes: `CarrierNetworkDiagnosticTest`, `secrets.TAILSCALE_AUTHKEY`, phone exit node `100.77.93.94`.
- Produces: Manual workflow with `without-tailscale` and `with-phone-exit-node` jobs; both run the same test command and test inputs. Only the latter connects to Tailscale and selects the phone exit node.

- [ ] **Step 1: Write a workflow configuration test**

Modify the appropriate existing deployment workflow test (or create a focused test) to assert the new workflow is `workflow_dispatch` only, contains distinct no-Tailscale and phone-exit-node jobs, runs `CarrierNetworkDiagnosticTest` in both, and never includes Hapag-Lloyd or the production run-once flag.

- [ ] **Step 2: Run the workflow configuration test and verify it fails**

Run: `./mvnw.cmd -Dtest=DeploymentConfigurationTest test`
Expected: FAIL because the diagnostic workflow does not yet exist.

- [ ] **Step 3: Implement the isolated diagnostic workflow**

Each job checks out the branch, installs Java 21 and Chrome, builds/tests with `CARRIER_NETWORK_DIAGNOSTIC=true`, and has a bounded timeout. The Tailscale job connects with the existing secret and sets `--exit-node=100.77.93.94`. No database or notification secrets are passed.

- [ ] **Step 4: Verify workflow configuration tests pass**

Run: `./mvnw.cmd -Dtest=DeploymentConfigurationTest test`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw.cmd test`
Expected: BUILD SUCCESS, zero failures; external browser integration remains skipped by default.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/shiparrival/CarrierNetworkDiagnosticTest.java .github/workflows/carrier-network-diagnostic.yml src/test/java/com/shiparrival/config/DeploymentConfigurationTest.java
git commit -m "Add carrier network diagnostic workflow"
```

### Task 3: Execute and report the comparison

- [ ] Manually dispatch `Carrier Network Diagnostic` once from GitHub Actions.
- [ ] Compare per-carrier success/results in each job; report failures by carrier without exposing payloads.
- [ ] Do not change or dispatch the daily monitoring workflow as part of this diagnostic.
