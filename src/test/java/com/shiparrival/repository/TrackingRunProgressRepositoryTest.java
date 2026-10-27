package com.shiparrival.repository;

import com.shiparrival.domain.TrackingRunProgress;
import com.shiparrival.domain.TrackingRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class TrackingRunProgressRepositoryTest {

    @Autowired
    private TrackingRunProgressRepository repository;

    private TrackingRunProgress run(String runId, LocalDateTime startedAt) {
        return repository.save(TrackingRunProgress.builder()
                .runId(runId)
                .status(TrackingRunStatus.RUNNING)
                .total(4)
                .processed(0)
                .updated(0)
                .failed(0)
                .origin("GITHUB_ACTIONS")
                .startedAt(startedAt)
                .build());
    }

    @Test
    @DisplayName("Recupera una ejecución por su identificador")
    void shouldFindByRunId() {
        run("run-1", LocalDateTime.of(2026, 10, 27, 8, 0));

        assertThat(repository.findByRunId("run-1")).isPresent();
        assertThat(repository.findByRunId("run-inexistente")).isEmpty();
    }

    @Test
    @DisplayName("El identificador de ejecución es único")
    void shouldRejectDuplicateRunId() {
        run("run-1", LocalDateTime.of(2026, 10, 27, 8, 0));

        assertThatThrownBy(() -> repository.saveAndFlush(TrackingRunProgress.builder()
                .runId("run-1")
                .status(TrackingRunStatus.RUNNING)
                .startedAt(LocalDateTime.of(2026, 10, 27, 9, 0))
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("La última ejecución es la de inicio más reciente")
    void shouldFindLatestRun() {
        run("run-viejo", LocalDateTime.of(2026, 10, 26, 8, 0));
        run("run-nuevo", LocalDateTime.of(2026, 10, 27, 8, 0));

        assertThat(repository.findTopByOrderByStartedAtDesc())
                .isPresent()
                .get()
                .extracting(TrackingRunProgress::getRunId)
                .isEqualTo("run-nuevo");
    }

    @Test
    @DisplayName("Sin ejecuciones registradas no hay última ejecución")
    void shouldReturnEmptyWhenNoRuns() {
        assertThat(repository.findTopByOrderByStartedAtDesc()).isEmpty();
    }
}
