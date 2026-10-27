package com.shiparrival.service;

import com.shiparrival.domain.TrackingRunProgress;
import com.shiparrival.domain.TrackingRunStatus;
import com.shiparrival.service.dto.LiveTrackingStatus;
import com.shiparrival.service.dto.TrackingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveTrackingStatusServiceTest {

    private static final long RUN_ID = 987654L;

    private TrackingProgressService progressService;
    private GitHubActionsClient gitHubActionsClient;
    private TrackingSchedulerService trackingSchedulerService;
    private LiveTrackingStatusService service;

    @BeforeEach
    void setUp() {
        progressService = mock(TrackingProgressService.class);
        gitHubActionsClient = mock(GitHubActionsClient.class);
        trackingSchedulerService = mock(TrackingSchedulerService.class);
        service = new LiveTrackingStatusService(progressService, gitHubActionsClient,
                trackingSchedulerService, 25);
    }

    private GitHubRunState run(String status, String conclusion) {
        return new GitHubRunState(RUN_ID, status, conclusion,
                "https://github.com/krcondorig/ship-arrival/actions/runs/" + RUN_ID, null);
    }

    private TrackingRunProgress row(TrackingRunStatus status, LocalDateTime heartbeat) {
        return TrackingRunProgress.builder()
                .runId(String.valueOf(RUN_ID)).status(status).total(4)
                .processed(2).updated(1).failed(1)
                .startedAt(LocalDateTime.now().minusMinutes(40))
                .lastHeartbeatAt(heartbeat)
                .build();
    }

    @Test
    @DisplayName("Cuando falla el paso de Tailscale, avisa del teléfono apagado")
    void shouldExplainPhoneOffline() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("completed", "failure")));
        when(gitHubActionsClient.findFailedStep(RUN_ID))
                .thenReturn(Optional.of("Connect to Tailscale (Phone Exit Node)"));
        when(progressService.find(String.valueOf(RUN_ID))).thenReturn(Optional.empty());

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("FAILED");
        assertThat(status.message()).isEqualTo(LiveTrackingStatusService.PHONE_OFFLINE_MESSAGE);
        assertThat(status.running()).isFalse();
    }

    @Test
    @DisplayName("Si falla otro paso, el mensaje es genérico y apunta a GitHub")
    void shouldNotBlamePhoneForOtherFailures() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("completed", "failure")));
        when(gitHubActionsClient.findFailedStep(RUN_ID)).thenReturn(Optional.of("Build application"));
        when(progressService.find(String.valueOf(RUN_ID))).thenReturn(Optional.empty());

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("FAILED");
        assertThat(status.message()).isEqualTo(LiveTrackingStatusService.GENERIC_FAILURE_MESSAGE);
        assertThat(status.runUrl()).contains("/actions/runs/" + RUN_ID);
    }

    @Test
    @DisplayName("Una ejecución en cola no muestra porcentaje")
    void shouldReportQueued() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("queued", null)));
        when(progressService.find(String.valueOf(RUN_ID))).thenReturn(Optional.empty());

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("QUEUED");
        assertThat(status.running()).isTrue();
        assertThat(status.total()).isZero();
    }

    @Test
    @DisplayName("Mientras el runner arranca, la fase es PREPARING")
    void shouldReportPreparing() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("in_progress", null)));
        when(progressService.find(String.valueOf(RUN_ID))).thenReturn(Optional.empty());

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("PREPARING");
        assertThat(status.running()).isTrue();
        assertThat(status.total()).isZero();
    }

    @Test
    @DisplayName("Con avance publicado, la fase es RUNNING con contadores")
    void shouldReportRunningProgress() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("in_progress", null)));
        when(progressService.find(String.valueOf(RUN_ID)))
                .thenReturn(Optional.of(row(TrackingRunStatus.RUNNING, LocalDateTime.now())));

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("RUNNING");
        assertThat(status.running()).isTrue();
        assertThat(status.total()).isEqualTo(4);
        assertThat(status.processed()).isEqualTo(2);
        assertThat(status.updated()).isEqualTo(1);
        assertThat(status.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sin latido reciente, la ejecución se da por perdida en vez de esperar para siempre")
    void shouldMarkStaleRunAsFailed() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.empty());
        when(progressService.find(String.valueOf(RUN_ID)))
                .thenReturn(Optional.of(row(TrackingRunStatus.RUNNING, LocalDateTime.now().minusMinutes(40))));

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("FAILED");
        assertThat(status.running()).isFalse();
        assertThat(status.message()).contains("no reporta avance");
    }

    @Test
    @DisplayName("Una ejecución exitosa termina en COMPLETED")
    void shouldReportCompleted() {
        when(gitHubActionsClient.findRun(RUN_ID)).thenReturn(Optional.of(run("completed", "success")));
        when(progressService.find(String.valueOf(RUN_ID)))
                .thenReturn(Optional.of(row(TrackingRunStatus.COMPLETED, LocalDateTime.now())));

        LiveTrackingStatus status = service.resolve(RUN_ID);

        assertThat(status.phase()).isEqualTo("COMPLETED");
        assertThat(status.running()).isFalse();
        assertThat(status.processed()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sin runId se muestra el ciclo local en proceso")
    void shouldFallBackToLocalCycle() {
        when(trackingSchedulerService.getTrackingStatus()).thenReturn(TrackingStatus.builder()
                .running(true).total(3).processed(1).updated(1).failed(0)
                .message("Monitoreando...").build());

        LiveTrackingStatus status = service.resolve(null);

        assertThat(status.phase()).isEqualTo("RUNNING");
        assertThat(status.running()).isTrue();
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.processed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sin monitoreo local la fase es UNKNOWN")
    void shouldReportUnknownWhenIdle() {
        when(trackingSchedulerService.getTrackingStatus()).thenReturn(TrackingStatus.idle());

        LiveTrackingStatus status = service.resolve(null);

        assertThat(status.phase()).isEqualTo("UNKNOWN");
        assertThat(status.running()).isFalse();
    }
}
