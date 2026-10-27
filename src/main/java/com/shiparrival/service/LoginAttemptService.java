package com.shiparrival.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freno a la fuerza bruta sobre el formulario de acceso.
 *
 * <p>Tras {@value #MAX_ATTEMPTS} intentos fallidos para el mismo usuario, se
 * bloquea ese usuario durante {@value #LOCK_MINUTES} minutos. El estado vive en
 * memoria: es suficiente porque el dashboard corre como una sola instancia.</p>
 */
@Service
public class LoginAttemptService {

    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCK_MINUTES = 15;

    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(Clock.systemDefaultZone());
    }

    public LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String username) {
        Attempt attempt = attempts.get(key(username));
        return attempt != null
                && attempt.lockedUntil() != null
                && clock.instant().isBefore(attempt.lockedUntil());
    }

    public long remainingLockSeconds(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null || attempt.lockedUntil() == null) {
            return 0;
        }
        long seconds = Duration.between(clock.instant(), attempt.lockedUntil()).getSeconds();
        return Math.max(0, seconds);
    }

    public void recordFailure(String username) {
        attempts.compute(key(username), (ignored, current) -> {
            Instant now = clock.instant();

            if (current == null
                    || (current.lockedUntil() != null && !now.isBefore(current.lockedUntil()))) {
                current = new Attempt(0, null);
            }
            if (current.lockedUntil() != null) {
                return current;
            }

            int failures = current.failures() + 1;
            if (failures >= MAX_ATTEMPTS) {
                return new Attempt(0, now.plus(Duration.ofMinutes(LOCK_MINUTES)));
            }
            return new Attempt(failures, null);
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private record Attempt(int failures, Instant lockedUntil) {
    }
}
