package com.shiparrival.service;

import com.shiparrival.domain.TrackingRunProgress;
import com.shiparrival.domain.TrackingRunStatus;
import com.shiparrival.repository.TrackingRunProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Publica el avance del monitoreo en la base compartida.
 *
 * <p>El runner de GitHub Actions y el dashboard son procesos distintos: la única
 * forma de que el botón muestre avance real es que el runner escriba aquí y el
 * dashboard lea de aquí.</p>
 *
 * <p>Los errores de escritura se registran pero no interrumpen el rastreo: perder
 * una actualización de la barra es molesto, perder el monitoreo completo no.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingProgressService {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final TrackingRunProgressRepository repository;

    @Transactional
    public TrackingRunProgress start(String runId, String origin) {
        TrackingRunProgress run = repository.findByRunId(runId)
                .orElseGet(() -> TrackingRunProgress.builder().runId(runId).build());

        run.setStatus(TrackingRunStatus.RUNNING);
        run.setOrigin(origin);
        run.setStartedAt(LocalDateTime.now());
        run.setLastHeartbeatAt(LocalDateTime.now());
        run.setFinishedAt(null);
        run.setMessage(null);
        return repository.save(run);
    }

    @Transactional
    public void update(String runId, int total, int processed, int updated, int failed) {
        repository.findByRunId(runId).ifPresent(run -> {
            run.setTotal(total);
            run.setProcessed(processed);
            run.setUpdated(updated);
            run.setFailed(failed);
            run.setLastHeartbeatAt(LocalDateTime.now());
            repository.save(run);
        });
    }

    @Transactional
    public void complete(String runId, int total, int processed, int updated, int failed) {
        repository.findByRunId(runId).ifPresent(run -> {
            run.setTotal(total);
            run.setProcessed(processed);
            run.setUpdated(updated);
            run.setFailed(failed);
            run.setStatus(TrackingRunStatus.COMPLETED);
            run.setFinishedAt(LocalDateTime.now());
            run.setLastHeartbeatAt(LocalDateTime.now());
            run.setMessage(failed > 0 ? "Monitoreo finalizado con fallos" : "Monitoreo finalizado");
            repository.save(run);
        });
    }

    @Transactional
    public void fail(String runId, String message) {
        repository.findByRunId(runId).ifPresent(run -> {
            run.setStatus(TrackingRunStatus.FAILED);
            run.setFinishedAt(LocalDateTime.now());
            run.setLastHeartbeatAt(LocalDateTime.now());
            run.setMessage(truncate(message));
            repository.save(run);
        });
    }

    @Transactional(readOnly = true)
    public Optional<TrackingRunProgress> find(String runId) {
        return repository.findByRunId(runId);
    }

    @Transactional(readOnly = true)
    public Optional<TrackingRunProgress> findLatest() {
        return repository.findTopByOrderByStartedAtDesc();
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
