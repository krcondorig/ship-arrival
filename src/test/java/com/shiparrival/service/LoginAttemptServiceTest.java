package com.shiparrival.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"), ZoneId.of("America/Lima"));
        service = new LoginAttemptService(clock);
    }

    @Test
    @DisplayName("Cinco fallos bloquean la cuenta durante 15 minutos")
    void shouldBlockAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("admin")).isTrue();
        assertThat(service.remainingLockSeconds("admin")).isEqualTo(900);
    }

    @Test
    @DisplayName("El bloqueo expira pasados los 15 minutos")
    void shouldExpireLock() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        clock.advance(Duration.ofMinutes(16));

        assertThat(service.isBlocked("admin")).isFalse();
    }

    @Test
    @DisplayName("Un login exitoso reinicia el contador")
    void shouldResetOnSuccess() {
        service.recordFailure("admin");
        service.recordFailure("admin");
        service.recordSuccess("admin");

        for (int i = 0; i < 4; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("admin")).isFalse();
    }

    @Test
    @DisplayName("El bloqueo es por usuario, no global")
    void shouldIsolateUsers() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin");
        }

        assertThat(service.isBlocked("otro")).isFalse();
    }

    /**
     * Reloj controlable para probar el vencimiento del bloqueo sin esperar.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
