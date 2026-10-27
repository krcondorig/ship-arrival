package com.shiparrival;

import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingRunOnceRunnerContextTest {

    private TrackingExecutionSummary emptySummary() {
        return TrackingExecutionSummary.builder()
                .totalProcessed(0).updatedCount(0).failedCount(0).alertsSentCount(0)
                .build();
    }

    @Test
    @DisplayName("Publica el identificador recibido del workflow antes de monitorear")
    void shouldUseProvidedRunId() {
        TrackingSchedulerService service = mock(TrackingSchedulerService.class);
        when(service.runTrackingCycle()).thenReturn(emptySummary());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(service, exitCode::set, "run-9", "GITHUB_ACTIONS").run(null);

        verify(service).setRunContext("run-9", "GITHUB_ACTIONS");
        verify(service).runTrackingCycle();
    }

    @Test
    @DisplayName("Genera un identificador y origen local cuando no vienen del entorno")
    void shouldGenerateRunIdWhenMissing() {
        TrackingSchedulerService service = mock(TrackingSchedulerService.class);
        when(service.runTrackingCycle()).thenReturn(emptySummary());
        AtomicInteger exitCode = new AtomicInteger(-1);

        new TrackingRunOnceRunner(service, exitCode::set, "", null).run(null);

        verify(service).setRunContext(anyString(), eq("LOCAL"));
        verify(service).runTrackingCycle();
    }
}
