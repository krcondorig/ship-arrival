# Task 2 report — bounded adapter-call coordinator

## Implemented
- Added `CarrierTrackingCoordinator`: separate two-worker executor with bounded queue, explicit opt-in gate, MSC/Maersk-only submissions, asynchronous `CompletableFuture<TrackingResult>` return, per-carrier future chaining, cycle drain/cancel methods, and Spring destruction hook.
- Added `TrackingParallelismConfiguration` and tests demonstrating simultaneous stub MSC+Maersk calls, plus test guarding Maersk synchronization.
- The pilot remains disabled by default. No workflow changes made.

## Exact requirements and disposition
- Dedicated bounded two-thread executor separate from `trackingTaskExecutor`: done.
- Only MSC/Maersk submissions; maximum one queued/running sequence per carrier: implemented by same-carrier future chaining. Overall executor worker count is two.
- Cycle-scoped drain/cancel and managed shutdown: implemented. Scheduler non-pilot barrier integration belongs to Task 3 and is not claimed here.
- MSC serialization: existing `SeleniumMscTrackingClient` monitor is unchanged.
- Maersk proof of independent per-call browser creation/cleanup and no cross-response sharing: **not established**. ChromeDriver construction is internal and hard-wired, and no safe deterministic isolation test seam exists in the present client. `fetchTrackingJson` remains `synchronized`; therefore effective Maersk concurrency is not enabled and must not be claimed. Parallel enablement for Maersk is blocked pending an injectable browser factory / revised isolation design and a demonstrative test.
- Configuration/workflow: pilot remains off; workflow untouched.

## Verification
Command: `./mvnw.cmd -B "-Dtest=CarrierTrackingCoordinatorTest,SeleniumMaerskTrackingClientConcurrencyTest" test`
Result: 2 tests, 0 failures/errors/skips; BUILD SUCCESS.

## Scope
No scheduler integration, activation, or workflow changes. Existing untracked design/plan files were pre-existing and are not included in the commit.
