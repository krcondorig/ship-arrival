package com.shiparrival.service;

import com.shiparrival.domain.TrackingRunProgress;
import com.shiparrival.domain.TrackingRunStatus;
import com.shiparrival.service.dto.LiveTrackingStatus;
import com.shiparrival.service.dto.TrackingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Traduce el estado de una ejecución (fila de progreso + estado en GitHub) a algo
 * que el dashboard pueda mostrar sin ambigüedad.
 *
 * <p>El caso importante es el fallo del exit node: cuando el teléfono está
 * apagado, el workflow muere en el paso de Tailscale y el usuario debe leer por
 * qué, no quedarse mirando una barra que nunca avanza.</p>
 */
@Slf4j
@Service
public class LiveTrackingStatusService {

    static final String PHONE_OFFLINE_MESSAGE =
            "Tu teléfono no responde como nodo de salida. Verifica que esté encendido, "
                    + "con datos móviles y Tailscale activo.";
    static final String GENERIC_FAILURE_MESSAGE =
            "El monitoreo falló en la nube. Revisa el detalle en GitHub Actions.";

    private final TrackingProgressService progressService;
    private final GitHubActionsClient gitHubActionsClient;
    private final TrackingSchedulerService trackingSchedulerService;
    private final long staleMinutes;

    public LiveTrackingStatusService(TrackingProgressService progressService,
                                     GitHubActionsClient gitHubActionsClient,
                                     TrackingSchedulerService trackingSchedulerService,
                                     @Value("${tracking.remote.stale-minutes:25}") long staleMinutes) {
        this.progressService = progressService;
        this.gitHubActionsClient = gitHubActionsClient;
        this.trackingSchedulerService = trackingSchedulerService;
        this.staleMinutes = staleMinutes;
    }

    public LiveTrackingStatus resolve(Long runId) {
        if (runId == null) {
            return fromLocalProcess();
        }

        Optional<TrackingRunProgress> progress = progressService.find(String.valueOf(runId));
        Optional<GitHubRunState> run = gitHubActionsClient.findRun(runId);
        String runUrl = run.map(GitHubRunState::htmlUrl).orElse(null);

        if (run.isPresent()) {
            GitHubRunState state = run.get();

            if (state.isFailure()) {
                String failedStep = state.failedStepName() != null
                        ? state.failedStepName()
                        : gitHubActionsClient.findFailedStep(state.id()).orElse(null);
                if (isPhoneStep(failedStep)) {
                    return failed(PHONE_OFFLINE_MESSAGE, runUrl, progress);
                }
                return failed(GENERIC_FAILURE_MESSAGE, runUrl, progress);
            }
            if (state.isSuccess()) {
                return completed(runUrl, progress);
            }
            if (state.isQueued() && progress.isEmpty()) {
                return simple("QUEUED", true, "En cola: GitHub está preparando la ejecución...", runUrl);
            }
            if (state.isInProgress() && progress.isEmpty()) {
                return simple("PREPARING", true, "Preparando el monitoreo en la nube...", runUrl);
            }
        }

        if (progress.isPresent()) {
            TrackingRunProgress row = progress.get();
            if (row.getStatus() == TrackingRunStatus.RUNNING && isStale(row)) {
                return failed("El monitoreo no reporta avance desde hace más de " + staleMinutes
                        + " minutos. Revisa que el teléfono esté encendido y con Tailscale activo.",
                        runUrl, progress);
            }
            return fromProgress(row, runUrl);
        }

        return simple("UNKNOWN", false, "No se encontró información de esta ejecución.", runUrl);
    }

    private LiveTrackingStatus fromProgress(TrackingRunProgress row, String runUrl) {
        boolean running = row.getStatus() == TrackingRunStatus.RUNNING;
        String phase = switch (row.getStatus()) {
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case FAILED, STALE -> "FAILED";
        };
        String message = row.getMessage() != null
                ? row.getMessage()
                : (running ? "Monitoreando..." : null);

        return new LiveTrackingStatus(phase, running, row.getTotal(), row.getProcessed(),
                row.getUpdated(), row.getFailed(), 0, null, message, runUrl, List.of());
    }

    private LiveTrackingStatus completed(String runUrl, Optional<TrackingRunProgress> progress) {
        return progress.map(row -> new LiveTrackingStatus("COMPLETED", false, row.getTotal(),
                        row.getProcessed(), row.getUpdated(), row.getFailed(), 0, null,
                        row.getMessage() != null ? row.getMessage() : "Monitoreo finalizado", runUrl, List.of()))
                .orElseGet(() -> simple("COMPLETED", false, "Monitoreo finalizado", runUrl));
    }

    private LiveTrackingStatus failed(String message, String runUrl,
                                      Optional<TrackingRunProgress> progress) {
        return progress.map(row -> new LiveTrackingStatus("FAILED", false, row.getTotal(),
                        row.getProcessed(), row.getUpdated(), row.getFailed(), 0, null,
                        message, runUrl, List.of()))
                .orElseGet(() -> simple("FAILED", false, message, runUrl));
    }

    private LiveTrackingStatus simple(String phase, boolean running, String message, String runUrl) {
        return new LiveTrackingStatus(phase, running, 0, 0, 0, 0, 0, null, message, runUrl, List.of());
    }

    private LiveTrackingStatus fromLocalProcess() {
        TrackingStatus local = trackingSchedulerService.getTrackingStatus();

        if (local.isRunning()) {
            String phase = local.getTotal() > 0 ? "RUNNING" : "PREPARING";
            return new LiveTrackingStatus(phase, true, local.getTotal(), local.getProcessed(),
                    local.getUpdated(), local.getFailed(), local.getAlertsSent(),
                    local.getCurrentTrackingNumber(),
                    local.getMessage() != null ? local.getMessage() : "Monitoreando...",
                    null, copyDetails(local));
        }
        if (local.getFinishedAt() != null) {
            String phase = local.getFailed() > 0 ? "FAILED" : "COMPLETED";
            return new LiveTrackingStatus(phase, false, local.getTotal(), local.getProcessed(),
                    local.getUpdated(), local.getFailed(), local.getAlertsSent(), null,
                    local.getMessage(), null, copyDetails(local));
        }
        return simple("UNKNOWN", false,
                local.getMessage() != null ? local.getMessage() : "Sin monitoreo en curso", null);
    }

    private List<String> copyDetails(TrackingStatus local) {
        return local.getDetails() != null ? List.copyOf(local.getDetails()) : List.of();
    }

    private boolean isPhoneStep(String step) {
        return step != null && (step.contains("Tailscale") || step.contains("Exit Node"));
    }

    private boolean isStale(TrackingRunProgress row) {
        LocalDateTime reference = row.getLastHeartbeatAt() != null
                ? row.getLastHeartbeatAt()
                : row.getStartedAt();
        return reference != null && reference.isBefore(LocalDateTime.now().minusMinutes(staleMinutes));
    }
}
