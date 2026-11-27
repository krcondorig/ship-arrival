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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Traduce el estado de una ejecución (fila de progreso + estado en GitHub) a algo
 * que el dashboard pueda mostrar sin ambigüedad.
 *
 * <p>El caso importante es el fallo del egreso: si el proxy SOCKS5 de Cloudflare
 * WARP no conecta (o la verificación de salida falla), el workflow muere antes de
 * consultar ninguna naviera y el usuario debe leer por qué, no quedarse mirando
 * una barra que nunca avanza.</p>
 */
@Slf4j
@Service
public class LiveTrackingStatusService {

    public static final String EGRESS_OFFLINE_MESSAGE =
            "El egreso por Cloudflare WARP no responde. Revisa el paso de conexión "
                    + "del proxy SOCKS5 en GitHub Actions.";
    static final String GENERIC_FAILURE_MESSAGE =
            "El monitoreo falló en la nube. Revisa el detalle en GitHub Actions.";

    /**
     * Pasos del workflow que, si fallan, dejan el ciclo sin salida a internet.
     * Se comparan por nombre normalizado para no confundirlos con pasos que solo
     * mencionan WARP de pasada (p. ej. "Build application (before WARP)" o
     * "Cache WARP packages").
     */
    private static final Set<String> EGRESS_STEPS = Set.of(
            "install cloudflare-warp",
            "connect warp through socks5 proxy",
            "verify egress through warp socks5");

    /** Marcadores heredados de la etapa en que el egreso salía por Tailscale. */
    private static final List<String> LEGACY_EGRESS_MARKERS = List.of("tailscale", "exit node");

    /** Palabras que sí describen una salida a internet; sin una de ellas no hay egreso. */
    private static final List<String> EGRESS_TERMS = List.of("exit node", "egress", "salida", "proxy", "socks5");

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
                if (isEgressStep(failedStep)) {
                    return failed(EGRESS_OFFLINE_MESSAGE, runUrl, progress);
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
                        + " minutos. Revisa el paso de egreso por Cloudflare WARP en GitHub Actions.",
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

    /**
     * Indica si el paso fallido dejó el ciclo sin egreso a internet.
     *
     * <p>Se reconocen los pasos reales del workflow (instalación de WARP, conexión
     * del proxy SOCKS5 y verificación de la salida) y, por compatibilidad histórica,
     * cualquier paso de Tailscale que además hable de salida. Un paso que solo
     * menciona "Tailscale" de pasada —como el prefetch de ChromeDriver— no cuenta.</p>
     */
    private boolean isEgressStep(String step) {
        if (step == null) {
            return false;
        }
        String normalized = step.toLowerCase(Locale.ROOT).trim();
        if (EGRESS_STEPS.contains(normalized)) {
            return true;
        }
        boolean legacyMarker = LEGACY_EGRESS_MARKERS.stream().anyMatch(normalized::contains);
        boolean egressTerm = EGRESS_TERMS.stream().anyMatch(normalized::contains);
        return legacyMarker && egressTerm;
    }

    private boolean isStale(TrackingRunProgress row) {
        LocalDateTime reference = row.getLastHeartbeatAt() != null
                ? row.getLastHeartbeatAt()
                : row.getStartedAt();
        return reference != null && reference.isBefore(LocalDateTime.now().minusMinutes(staleMinutes));
    }
}
