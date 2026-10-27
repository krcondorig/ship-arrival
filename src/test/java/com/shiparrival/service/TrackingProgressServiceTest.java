package com.shiparrival.service;

import com.shiparrival.domain.TrackingRunProgress;
import com.shiparrival.domain.TrackingRunStatus;
import com.shiparrival.repository.TrackingRunProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackingProgressServiceTest {

    private TrackingRunProgressRepository repository;
    private TrackingProgressService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrackingRunProgressRepository.class);
        when(repository.save(any(TrackingRunProgress.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new TrackingProgressService(repository);
    }

    @Test
    @DisplayName("Crea la ejecución si no existe")
    void shouldCreateRunWhenMissing() {
        when(repository.findByRunId("run-1")).thenReturn(Optional.empty());

        TrackingRunProgress run = service.start("run-1", "GITHUB_ACTIONS");

        assertThat(run.getStatus()).isEqualTo(TrackingRunStatus.RUNNING);
        assertThat(run.getOrigin()).isEqualTo("GITHUB_ACTIONS");
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("Reutiliza la fila que dejó el dashboard al disparar")
    void shouldReuseRunCreatedByDashboard() {
        TrackingRunProgress existing = TrackingRunProgress.builder()
                .id(99L).runId("run-1").status(TrackingRunStatus.RUNNING)
                .origin("DASHBOARD").startedAt(LocalDateTime.now().minusMinutes(3))
                .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(existing));

        TrackingRunProgress run = service.start("run-1", "GITHUB_ACTIONS");

        assertThat(run.getId()).isEqualTo(99L);
        assertThat(run.getOrigin()).isEqualTo("GITHUB_ACTIONS");
    }

    @Test
    @DisplayName("Publica el avance contador a contador")
    void shouldUpdateCounters() {
        TrackingRunProgress run = TrackingRunProgress.builder()
                .runId("run-1").status(TrackingRunStatus.RUNNING).total(4).build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(run));

        service.update("run-1", 4, 2, 1, 1);

        assertThat(run.getTotal()).isEqualTo(4);
        assertThat(run.getProcessed()).isEqualTo(2);
        assertThat(run.getUpdated()).isEqualTo(1);
        assertThat(run.getFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Marca la ejecución como completada con su hora de fin")
    void shouldMarkCompleted() {
        TrackingRunProgress run = TrackingRunProgress.builder()
                .runId("run-1").status(TrackingRunStatus.RUNNING).build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(run));

        service.complete("run-1", 4, 4, 4, 0);

        assertThat(run.getStatus()).isEqualTo(TrackingRunStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getProcessed()).isEqualTo(4);
    }

    @Test
    @DisplayName("Marca la ejecución como fallida con el motivo")
    void shouldMarkFailed() {
        TrackingRunProgress run = TrackingRunProgress.builder()
                .runId("run-1").status(TrackingRunStatus.RUNNING).build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(run));

        service.fail("run-1", "Sin conexión con el exit node");

        assertThat(run.getStatus()).isEqualTo(TrackingRunStatus.FAILED);
        assertThat(run.getMessage()).contains("exit node");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Consultar una ejecución inexistente no falla")
    void shouldReturnEmptyForUnknownRun() {
        when(repository.findByRunId("desconocida")).thenReturn(Optional.empty());

        assertThat(service.find("desconocida")).isEmpty();
    }
}
