# Manual Hapag-Lloyd ZenRows Test Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow the dashboard's manual GitHub Actions dispatch to test Hapag-Lloyd via ZenRows while keeping scheduled runs' current manual-check behavior.

**Architecture:** Pass `ZENROWS_API_KEY` to the workflow's tracking process from a GitHub Actions secret. Set `TRACKING_MANUAL_CHECK_CARRIERS` to an empty value only for a `workflow_dispatch` run; retain `HAPAG_LLOYD` for scheduled runs. The existing button still runs the full cycle over active shipments.

**Tech Stack:** GitHub Actions YAML, Maven/JUnit configuration tests.

**Spec:** User-approved scope in conversation: use the existing “Monitorear Ahora” button to run the manual workflow and test Hapag-Lloyd through ZenRows; do not alter daily scheduled behavior.

## Global Constraints

- Never put the ZenRows API key in tracked files or logs; read it from GitHub Actions secret `ZENROWS_API_KEY`.
- Scheduled workflow runs must continue to skip Hapag-Lloyd by default.
- Manual dispatch runs the full active-shipment cycle, not a single selected B/L.
- Automated tests must not make real ZenRows requests.

## Review Focus

- Scheduled event must retain `HAPAG_LLOYD` in manual-check carriers; pin with workflow configuration test.
- Manual dispatch must set the manual-check list empty; pin with workflow configuration test.
- The secret is passed only through the process environment, never echoed; pin with workflow configuration test.

---

### Task 1: Configure ZenRows and Hapag-Lloyd for manual workflow dispatch only

**Files:**
- Modify: `.github/workflows/daily-monitoring.yml`
- Test: `src/test/java/com/shiparrival/config/DeploymentConfigurationTest.java`

**Interfaces:**
- Consumes: GitHub Actions `secrets.ZENROWS_API_KEY`, workflow event `github.event_name`.
- Produces: tracking process env `ZENROWS_API_KEY`; `TRACKING_MANUAL_CHECK_CARRIERS` empty for manual dispatch and `HAPAG_LLOYD` for scheduled execution.

- [ ] **Step 1: Add failing assertions** in a test that reads the workflow text and verifies the ZenRows secret mapping, event-dependent manual-check setting, and no echo of the secret.
- [ ] **Step 2: Run** `./mvnw -Dtest=DeploymentConfigurationTest test`; expected: fail because workflow configuration does not yet contain these settings.
- [ ] **Step 3: Update** the workflow run step to set `ZENROWS_API_KEY: ${{ secrets.ZENROWS_API_KEY }}` and `TRACKING_MANUAL_CHECK_CARRIERS: ${{ github.event_name == 'workflow_dispatch' && '' || 'HAPAG_LLOYD' }}`; do not print either secret or use it in shell command text.
- [ ] **Step 4: Run** `./mvnw -Dtest=DeploymentConfigurationTest test`; expected: pass.
- [ ] **Step 5: Run** `./mvnw test` and `git diff --check`; expected: full suite passes and no whitespace errors.
