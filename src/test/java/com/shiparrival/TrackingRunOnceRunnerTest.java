package com.shiparrival;

import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingRunOnceRunnerTest {

    private final TrackingSchedulerService schedulerService =
            mock(TrackingSchedulerService.class);

    @Test
    @DisplayName("Ejecuta el ciclo de monitoreo y termina con código 0 cuando todo se actualiza")
    void shouldRunCycleAndExitWithZeroWhenEverythingSucceeds() {
        when(schedulerService.runTrackingCycle()).thenReturn(
                TrackingExecutionSummary.builder()
                        .totalProcessed(2)
                        .updatedCount(2)
                        .failedCount(0)
                        .alertsSentCount(1)
                        .build());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        verify(schedulerService).runTrackingCycle();
        assertThat(exitCode.get()).isZero();
    }

    @Test
    @DisplayName("Termina con código 1 cuando todas las guías fallan")
    void shouldExitWithOneWhenEveryShipmentFails() {
        when(schedulerService.runTrackingCycle()).thenReturn(
                TrackingExecutionSummary.builder()
                        .totalProcessed(3)
                        .updatedCount(0)
                        .failedCount(3)
                        .build());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        assertThat(exitCode.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Termina con código 0 cuando no hay embarques activos que monitorear")
    void shouldExitWithZeroWhenThereAreNoActiveShipments() {
        when(schedulerService.runTrackingCycle()).thenReturn(
                TrackingExecutionSummary.builder()
                        .totalProcessed(0)
                        .updatedCount(0)
                        .failedCount(0)
                        .build());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        assertThat(exitCode.get()).isZero();
    }

    @Test
    @DisplayName("Termina con código 0 cuando al menos una guía se actualizó aunque otras fallen")
    void shouldExitWithZeroWhenAtLeastOneShipmentWasUpdated() {
        when(schedulerService.runTrackingCycle()).thenReturn(
                TrackingExecutionSummary.builder()
                        .totalProcessed(2)
                        .updatedCount(1)
                        .failedCount(1)
                        .build());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        assertThat(exitCode.get()).isZero();
    }

    @Test
    @DisplayName("Termina con código 1 cuando el ciclo devuelve un error global")
    void shouldExitWithOneWhenCycleReturnsGlobalError() {
        when(schedulerService.runTrackingCycle()).thenReturn(
                TrackingExecutionSummary.builder()
                        .totalProcessed(0)
                        .errorMessage("Error al obtener embarques activos")
                        .build());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        assertThat(exitCode.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Termina con código 1 cuando el ciclo lanza una excepción inesperada")
    void shouldExitWithOneWhenCycleThrows() {
        when(schedulerService.runTrackingCycle())
                .thenThrow(new IllegalStateException("fallo de conexión"));
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(schedulerService, exitCode::set).run(null);

        assertThat(exitCode.get()).isEqualTo(1);
    }
}
