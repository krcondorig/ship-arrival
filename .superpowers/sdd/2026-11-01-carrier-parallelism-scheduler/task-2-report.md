# Task 2 report — bounded adapter-call coordinator

## Implemented
- Hardened `CarrierTrackingCoordinator` with cycle-scoped submission boundary, deferred same-carrier dispatch, and worker-completion tracking. Accepted work is either prevented from entering the adapter when the cycle is cancelled or has its adapter call interrupted and completion awaited.
- Drain and cancel/drain are serialized and reusable between cycles. Deferred predecessor callbacks consult cycle cancellation; cancelled successors complete without an adapter call. Individual result-future cancellation interrupts its active worker.
- Removed the unused `TrackingParallelismConfiguration`; the coordinator reads the existing property directly. Pilot remains disabled by default; workflow unchanged.
- Added deterministic cancellation coverage in addition to cross-carrier overlap and same-carrier serialization tests. Maersk `fetchTrackingJson` remains synchronized.

## Exact requirements and disposition
- Dedicated bounded two-thread executor separate from `trackingTaskExecutor`: done.
- Only MSC/Maersk submissions; maximum one queued/running sequence per carrier: implemented by same-carrier future chaining. Overall executor worker count is two.
- Cycle-scoped drain/cancel and managed shutdown: implemented. Scheduler non-pilot barrier integration belongs to Task 3 and is not claimed here.
- MSC serialization: existing `SeleniumMscTrackingClient` monitor is unchanged.
- Maersk proof of independent per-call browser creation/cleanup and no cross-response sharing: **not established**. ChromeDriver construction is internal and hard-wired, and no safe deterministic isolation test seam exists in the present client. `fetchTrackingJson` remains `synchronized`; therefore effective Maersk concurrency is not enabled and must not be claimed. Parallel enablement for Maersk is blocked pending an injectable browser factory / revised isolation design and a demonstrative test.
- Configuration/workflow: pilot remains off; workflow untouched.

## Verification
Command: `./mvnw.cmd -B "-Dtest=CarrierTrackingCoordinatorTest,SeleniumMaerskTrackingClientConcurrencyTest" test`
Result: **4 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS** (2026-11-01 16:07:39 -05:00).

The coordinator tests verify one MSC and one Maersk can overlap, repeated MSC submissions serialize while Maersk proceeds, max global active calls remains <=2 and per-carrier active calls <=1, cancellation prevents the deferred successor adapter call, and cancellation interrupts and waits for an active worker to exit. The Maersk test confirms its synchronization remains present.

Cancellation boundary: `CarrierTrackingAdapter.track` has no cancellation token or forced-stop API. The coordinator interrupts the worker and waits until its invocation returns; it cannot safely guarantee termination if a third-party adapter ignores interruption. In that case `cancelAndDrain` deliberately remains blocked rather than falsely reporting drained. No completion timeout is advertised.

## Scope
No scheduler integration, activation, or workflow changes. Existing untracked design/plan files were pre-existing and are not included in the commit.
