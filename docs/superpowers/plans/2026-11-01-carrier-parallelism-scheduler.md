

## Fix Report — coordinator lock-order inversion

- **Finding:** `submit()` synchronized on the coordinator instance before acquiring `lifecycle`, while `drain()`/`cancelAndDrain()` acquired `lifecycle` before the instance monitor. Concurrent submission and cycle shutdown could deadlock.
- **Fix:** Use `lifecycle` as the sole monitor for cycle/task state in `beginCycle`, `submit`, and drain cleanup. Drain operations no longer hold a separate drain monitor while waiting for worker completion, and no lock is held during that wait. Removed the unused `adapterStarted` field.
- **Verification:** `./mvnw.cmd "-Dtest=CarrierTrackingCoordinatorTest,SeleniumMaerskTrackingClientConcurrencyTest" test` — **BUILD SUCCESS**, 4 tests run, 0 failures, 0 errors, 0 skipped.
- **Notes:** Existing coordinator overlap, same-carrier serialization, cancellation/drain, and Maersk per-call isolation tests all passed.
