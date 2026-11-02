# Task 2 brief — bounded adapter-call coordinator and Maersk isolation

## Exact requirements

- Create `src/main/java/com/shiparrival/service/CarrierTrackingCoordinator.java`.
- Create `src/main/java/com/shiparrival/config/TrackingParallelismConfiguration.java`.
- Modify `src/main/java/com/shiparrival/adapter/maersk/SeleniumMaerskTrackingClient.java` only if isolation is proven.
- Add `CarrierTrackingCoordinatorTest` and a Maersk concurrency test.
- API: `submit(Carrier carrier, CarrierTrackingAdapter adapter, String trackingNumber) -> CompletableFuture<TrackingResult>`.
- Use a dedicated bounded two-thread executor, distinct from `trackingTaskExecutor`; only MSC and Maersk are eligible, with per-carrier max concurrency one. Honor the disabled-by-default `tracking.parallel.enabled` property. Keep MSC client serialization.
- Provide cycle-scoped drain/cancel and Spring-managed shutdown. No unbounded queue. Scheduler (Task 3) must drain before invoking non-pilot adapters.
- Prove eligible MSC+Maersk overlap, overall max 2 and per-carrier max 1 with deterministic synchronization.
- Remove Maersk `synchronized` only if test proves independent per-call ChromeDriver creation/cleanup and no cross-response sharing. Otherwise leave synchronized and explicitly report that Maersk parallel enablement is blocked pending redesign; do not claim effective concurrency.
- Keep pilot disabled and do not alter workflow.
- Run focused coordinator and Maersk tests, then commit only task-owned files.

## Authority and coordinator decision
Design authority: `docs/superpowers/specs/2026-11-01-carrier-parallelism-scheduler-design.md`.
Coordinator contract from ledger: bounded 2-thread executor, only MSC/Maersk, max 1 per carrier, submit returns CompletableFuture, cycle drain/cancel. No nonpilot overlap is enforced by scheduler in Task 3. Preserve MSC client serialization.
