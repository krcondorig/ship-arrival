# Carrier Parallelism Scheduler Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unstable pilot dispatch path with bounded MSC/Maersk overlap, globally spaced adapter starts, and exactly-once ordered result application.

**Architecture:** `CarrierTrackingCoordinator` owns only bounded adapter workers, per-carrier serialization, start spacing, lifecycle and telemetry. `TrackingSchedulerService` owns explicit per-input dispatch records and applies all outcomes and business effects sequentially in input order; abort cleanup drains workers before releasing adapter resources.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Maven Wrapper, `CompletableFuture`, bounded `ThreadPoolExecutor`, monotonic time.

**Spec:** `docs/superpowers/specs/2026-11-02-carrier-parallelism-scheduler-redesign.md`

## Global Constraints

- `tracking.parallel.enabled=false` remains the default.
- Maximum in flight is two total: one MSC and one Maersk.
- No nonpilot adapter overlaps any active pilot adapter.
- Courtesy uses the existing `tracking.request.delay-ms` value and is a minimum interval between coordinator-issued start authorizations; a same-carrier successor receives authorization no earlier than predecessor completion plus that delay. Arbitrary JVM descheduling after authorization is outside the physical-start guarantee.
- Manual-check shipments are omitted, never dispatched, and do not count as adapter starts.
- `MSMU1804864` and `275792562` stay on the serial route while pilot mode is enabled.
- Apply persistence, history, alerts, details and progress exactly once in original input order.
- Drain/cancel accepted work before releasing adapter resources; partial cycles do not run retention.
- No real tracking, shared-database mutation, notification, workflow change, Render change, or pilot enablement.

## Review Focus

- Executor rejection after a sibling was accepted: preserve and apply the accepted result; represent only the rejected guide as failed. Test in Task 2.
- Coordinator start authorizations under executor scheduling delay: enforce spacing at the final coordinator authorization boundary rather than submit time; document JVM descheduling caveat. Test in Task 1.
- Interruption during start-spacing and repeated interruption during drain: no later calls begin and resources are released only after workers end. Test in Task 2.
- Manual/excluded entries interleaved with pilot candidates: manual never dispatches; exclusions remain serial and barrier-safe. Test in Task 2.
- Out-of-order worker completion plus worker exception: all side effects and progress remain exactly-once and input-ordered. Test in Task 2.

---

### Task 1: Coordinator lifecycle, call-start pacing, and explicit worker outcomes

**Files:**
- Modify: `src/main/java/com/shiparrival/service/CarrierTrackingCoordinator.java`
- Test: `src/test/java/com/shiparrival/service/CarrierTrackingCoordinatorTest.java`

**Interfaces:**
- Consumes: `CarrierTrackingAdapter.track(String) -> TrackingResult`, `Carrier`, current bounded executor lifecycle.
- Produces: cycle-scoped coordinator operations for `beginCycle`, pilot submission, stable worker barriers, `drain`, `cancelAndDrain`, and observed peak in flight. Use `CompletableFuture<TrackingResult>` whose completion state distinguishes successful result, worker error, and dispatcher rejection; do not rely on a raw `Future` being `ExecutionException`-only.
- Add a package-visible injectable pacing seam/monotonic start gate for deterministic tests; production behavior must wait only the remaining interval and record the coordinator authorization immediately before adapter invocation. Track in-flight adapter calls only after authorization; interruption before authorization is a not-started/cancelled dispatch and is not counted as an adapter failure or duration.

- [ ] **Step 1: Add failing coordinator tests** for two distinct carriers whose starts are separated by at least `delayMs` while the first remains in flight; same-carrier successor starts only after predecessor completion plus delay; per-carrier maximum one; global peak at most two; rejected executor produces an identifiable per-submission failed completion; existing concurrent barrier/submission tests remain valid.
- [ ] **Step 2: Run the focused tests and verify RED** with failures specifically showing absent start-spacing or ambiguous rejection/worker state, not compilation errors.
- [ ] **Step 3: Implement coordinator pacing and worker state** in `CarrierTrackingCoordinator.java`. Keep adapter calls as the only worker responsibility, use monotonic time, preserve bounded admission, and report per-call duration/outcome and observed peak without sensitive shipment data.
- [ ] **Step 4: Run coordinator tests** with `./mvnw.cmd -B "-Dtest=CarrierTrackingCoordinatorTest" test`; expect all coordinator tests to pass.
- [ ] **Step 5: Review coordinator diff** for lock ordering, callbacks invoked while holding lifecycle locks, barrier admission races, and release only after worker completion.

### Task 2: Scheduler explicit dispatch records and ordered application

**Files:**
- Modify: `src/main/java/com/shiparrival/service/TrackingSchedulerService.java`
- Test: `src/test/java/com/shiparrival/service/TrackingSchedulerServiceTest.java`
- Test: `src/test/java/com/shiparrival/service/TrackingSchedulerProgressTest.java`

**Interfaces:**
- Consumes: Task 1 coordinator submission/barrier/completion APIs.
- Produces: an explicit per-input-position dispatch representation with distinct states for manual skip, serial-required, pilot-accepted, pilot-dispatch-failed, result-ready/result-failed, and applied. Never infer dispatch state from a null future or from pacing bookkeeping.

- [ ] **Step 1: Add/adjust failing scheduler tests** for: manual candidate never submitted; exact serial behavior for the two excluded B/Ls; lookup failure fallback; partial rejection while still applying accepted siblings; sibling worker failure isolation; out-of-order completion with input-order persistence/alerts/progress exactly once; nonpilot adapter waits until pilot drain; same-carrier completion-plus-delay; real thread interruption during spacing and repeated interruption during drain; no resource release before workers terminate; partial summary equals applied prefix and skips retention.
- [ ] **Step 2: Run each new/changed regression to verify RED** against the current implementation; capture the specific failing assertion for each behavioral group.
- [ ] **Step 3: Replace scheduler predispatch bookkeeping** with explicit per-index records. Dispatch at most the first eligible guide for each pilot carrier into the bounded coordinator. Associate each accepted future and adapter with its actual input index. Store lookup/rejection failure as a terminal per-index state. Manual entries are never dispatched. Excluded and nonpilot entries use serial calls only after waiting for all pilot workers.
- [ ] **Step 4: Apply records in original order** on the scheduler thread. Convert dispatch/worker errors to a guide-specific failed `TrackingResult`; run update, history, alert, details and progress exactly once. On interruption/fatal cycle failure stop new starts, cancel/drain accepted tasks, preserve only already-applied prefix, skip retention, and restore interrupt after resource release.
- [ ] **Step 5: Run focused scheduler tests** with `./mvnw.cmd -B "-Dtest=TrackingSchedulerServiceTest,TrackingSchedulerProgressTest,CarrierTrackingCoordinatorTest" test`; expect zero failures/errors and verify every previously failing regression.

### Task 3: Full verification, observability and independent review

**Files:**
- Review: `src/main/java/com/shiparrival/service/CarrierTrackingCoordinator.java`
- Review: `src/main/java/com/shiparrival/service/TrackingSchedulerService.java`
- Review: Task 1/2 tests and configuration references.
- Update: `.superpowers/sdd/2026-11-01-carrier-parallelism-scheduler/task-3-report.md`

**Interfaces:**
- Consumes: completed Task 1 and Task 2 behavior.
- Produces: verified, reviewable scheduler redesign with accurate evidence and no operational activation.

- [ ] **Step 1: Run full suite** with `./mvnw.cmd -B test`; record the actual total, failures, errors, and skipped count from this run.
- [ ] **Step 2: Verify scope and formatting** with `git diff --check`; inspect configuration/workflow diffs and prove the default remains false and no Actions/Render activation was introduced.
- [ ] **Step 3: Update Task 3 report** with exact focused/full commands, observed counts, remaining skips, and explicit statement that no live pilot was run.
- [ ] **Step 4: Request an independent review** of the complete scheduler/coordinator diff against the approved spec; resolve all important findings with regression tests before completion.
- [ ] **Step 5: Keep commits scoped** to approved scheduler/coordinator implementation and tests only; preserve unrelated existing plan/spec/Task 2 report modifications. Do not commit until independent review and verification are clean.
