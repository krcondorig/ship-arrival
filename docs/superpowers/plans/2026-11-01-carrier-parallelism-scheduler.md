

## Fix Report — coordinator lock-order inversion

- **Finding:** `submit()` synchronized on the coordinator instance before acquiring `lifecycle`, while `drain()`/`cancelAndDrain()` acquired `lifecycle` before the instance monitor. Concurrent submission and cycle shutdown could deadlock.
- **Fix:** Use `lifecycle` as the sole monitor for cycle/task state in `beginCycle`, `submit`, and drain cleanup. Drain operations no longer hold a separate drain monitor while waiting for worker completion, and no lock is held during that wait. Removed the unused `adapterStarted` field.
- **Verification:** `./mvnw.cmd "-Dtest=CarrierTrackingCoordinatorTest,SeleniumMaerskTrackingClientConcurrencyTest" test` — **BUILD SUCCESS**, 4 tests run, 0 failures, 0 errors, 0 skipped.
- **Notes:** Existing coordinator overlap, same-carrier serialization, cancellation/drain, and Maersk per-call isolation tests all passed.

## Fix Report — concurrent cycle drains

- **Finding:** Concurrent `drain()` and `cancelAndDrain()` calls could snapshot the same workers. The first could finish and clear `carrierTails`, allowing a new cycle to begin; the second could then clear the new cycle's tails.
- **Fix:** Serialize drain operations with a dedicated drain lock. The lock ordering is drain lock then lifecycle monitor; worker waiting occurs without holding `lifecycle`, so `submit()` and completion callbacks cannot deadlock against the drain wait.
- **Verification:** `./mvnw.cmd -q -Dtest=CarrierTrackingCoordinatorTest test` — exit code 0; 4 tests run, 0 failures, 0 errors, 0 skipped. Output included existing Mockito/Java dynamic-agent warnings.
- **Regression test:** Added `concurrentDrainDoesNotClearTailsForNextCycle` to exercise concurrent normal/cancel drains and then start and drain a subsequent cycle.
