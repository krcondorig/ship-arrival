# Reduce Session Timeout to 1 Hour and Release Session from User Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce session lock/expiration duration from 12 hours to 1 hour, and ensure administrators can release any user's session directly from the user management panel.

**Architecture:** 
- Modify `UserService.java` and `ActiveSessionService.java` to set session expiration to `now.plusHours(1)` instead of `now.plusHours(12)`.
- Verify the admin session release endpoint (`POST /users/{id}/release-session`) and button in `users.html`.
- Update unit tests expecting 12-hour expiration to expect 1-hour expiration.

**Tech Stack:** Java 21, Spring Boot 3, Thymeleaf, H2/PostgreSQL.

**Spec:** User request to reduce session timeout to 1 hour and provide a direct button to close/release user sessions from the user management panel.

## Global Constraints
- Java 21 LTS, Spring Boot 3.4+.
- All tests must pass successfully.

## Review Focus
- Session expiration time correctly calculated as 1 hour.
- Admin session release button correctly clears active session and bumps epoch.

---

### Task 1: Update Session Expiration Duration to 1 Hour

**Files:**
- Modify: `src/main/java/com/shiparrival/service/UserService.java:136`
- Modify: `src/main/java/com/shiparrival/service/ActiveSessionService.java:27`
- Modify: `src/test/java/com/shiparrival/service/UserServiceTest.java:108-118`
- Modify: `src/test/java/com/shiparrival/web/AuthControllerTest.java:70-71`

**Interfaces:**
- Consumes: Existing user authentication and session start logic.
- Produces: `startSession` sets session expiration to `now.plusHours(1)`.

- [ ] **Step 1: Update `UserService.java` and `ActiveSessionService.java`**
Change `now.plusHours(12)` to `now.plusHours(1)`.

- [ ] **Step 2: Update tests in `UserServiceTest.java` and `AuthControllerTest.java`**
Adjust expected expiration checks from `plusHours(11)` / next day `00:00` to `plusMinutes(55)` / `13:00`.

- [ ] **Step 3: Run unit tests**
Run: `./mvnw test -Dtest=UserServiceTest,AuthControllerTest`
Expected: PASS

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/shiparrival/service/UserService.java src/main/java/com/shiparrival/service/ActiveSessionService.java src/test/java/com/shiparrival/service/UserServiceTest.java src/test/java/com/shiparrival/web/AuthControllerTest.java
git commit -m "feat: reduce session timeout from 12 hours to 1 hour"
```

### Task 2: Verify User Session Release from Admin Panel & Full Suite

**Files:**
- Test: `src/test/java/com/shiparrival/web/UserAdminControllerTest.java`

**Interfaces:**
- Consumes: `POST /users/{id}/release-session`
- Produces: Successfully releases user session from admin panel.

- [ ] **Step 1: Run full test suite**
Run: `./mvnw test`
Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 2: Commit any final verification or documentation**
```bash
git commit --allow-empty -m "chore: verify admin user session release functionality"
```
