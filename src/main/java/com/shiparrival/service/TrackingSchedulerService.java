package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import com.shiparrival.service.dto.TrackingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TrackingSchedulerService {

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;
    private final CarrierTrackingAdapterFactory adapterFactory;
    private final TelegramNotificationService telegramNotificationService;
    private final DiscordNotificationService discordNotificationService;
    private final TaskExecutor trackingTaskExecutor;
    private final long delayMs;
    private CarrierTrackingCoordinator carrierTrackingCoordinator;
    private boolean parallelTrackingEnabled;

    /**
     * Navieras cuyo rastreo automático está deshabilitado: sus guías se marcan
     * como {@code MANUAL_CHECK} para que un operador consulte el ETA a mano.
     */
    private final Set<Carrier> manualCheckCarriers;

    private final AtomicBoolean trackingInProgress = new AtomicBoolean(false);
    private TrackingStatus trackingStatus = TrackingStatus.idle();

    /**
     * Colaborador opcional: si no está (por ejemplo en pruebas unitarias del
     * ciclo), el monitoreo funciona igual y simplemente no publica avance.
     */
    private TrackingProgressService progressService;

    /**
     * Colaborador opcional: la limpieza de retención. Si no está (por ejemplo en
     * pruebas unitarias del ciclo), el monitoreo funciona igual y no borra nada.
     */
    private ShipmentRetentionService retentionService;

    private volatile String runId;
    private volatile String origin;

    @Autowired
    public TrackingSchedulerService(ShipmentService shipmentService,
                                     ShipmentRepository shipmentRepository,
                                     CarrierTrackingAdapterFactory adapterFactory,
                                     TelegramNotificationService telegramNotificationService,
                                     DiscordNotificationService discordNotificationService,
                                     @Qualifier("trackingTaskExecutor") TaskExecutor trackingTaskExecutor,
                                     @Value("${tracking.request.delay-ms:2000}") long delayMs,
                                     @Value("${tracking.manual-check-carriers:HAPAG_LLOYD}") String manualCheckCarriersConfig) {
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
        this.adapterFactory = adapterFactory;
        this.telegramNotificationService = telegramNotificationService;
        this.discordNotificationService = discordNotificationService;
        this.trackingTaskExecutor = trackingTaskExecutor;
        this.delayMs = delayMs;
        this.manualCheckCarriers = parseCarriers(manualCheckCarriersConfig);
    }

    /**
     * Convierte la lista separada por comas de {@code tracking.manual-check-carriers}
     * en un conjunto de navieras. Una lista vacía reactiva el rastreo automático
     * de todas ellas; los nombres desconocidos se ignoran con una advertencia
     * para no tumbar el arranque por un error de configuración.
     */
    private static Set<Carrier> parseCarriers(String config) {
        Set<Carrier> carriers = new LinkedHashSet<>();
        if (config == null || config.isBlank()) {
            return carriers;
        }
        for (String raw : config.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                carriers.add(Carrier.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                log.warn("Naviera desconocida en tracking.manual-check-carriers: '{}'. Se ignora.", name);
            }
        }
        return carriers;
    }

    public boolean isTrackingInProgress() {
        return trackingInProgress.get();
    }

    @Autowired(required = false)
    public void setCarrierTrackingCoordinator(CarrierTrackingCoordinator coordinator) {
        this.carrierTrackingCoordinator = coordinator;
    }

    @Autowired(required = false)
    public void setParallelTrackingEnabled(@Value("${tracking.parallel.enabled:false}") boolean enabled) {
        this.parallelTrackingEnabled = enabled;
    }

    @Autowired(required = false)
    public void setProgressService(TrackingProgressService progressService) {
        this.progressService = progressService;
    }

    @Autowired(required = false)
    public void setRetentionService(ShipmentRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    /**
     * Identifica la ejecución cuyo avance se publicará en la base compartida.
     * Se llama justo antes de disparar el ciclo; sin contexto no se publica nada.
     */
    public void setRunContext(String runId, String origin) {
        this.runId = runId;
        this.origin = origin;
    }

    private void clearRunContext() {
        this.runId = null;
        this.origin = null;
    }

    public void setTrackingInProgress(boolean inProgress) {
        trackingInProgress.set(inProgress);
    }

    public synchronized TrackingStatus getTrackingStatus() {
        List<String> details = trackingStatus.getDetails() != null
                ? List.copyOf(trackingStatus.getDetails())
                : List.of();
        return trackingStatus.toBuilder()
                .details(details)
                .build();
    }

    /**
     * Inicia el ciclo en segundo plano y vuelve inmediatamente.
     *
     * @return true si el ciclo fue encolado; false si ya hay uno en progreso o el executor lo rechazó.
     */
    public boolean startTrackingCycleAsync() {
        if (!trackingInProgress.compareAndSet(false, true)) {
            log.warn("Se intentó iniciar un monitoreo asíncrono, pero ya hay uno en progreso.");
            return false;
        }

        markQueued();

        try {
            trackingTaskExecutor.execute(this::runTrackingCycleInBackground);
            return true;
        } catch (RuntimeException ex) {
            log.error("No se pudo encolar el monitoreo asíncrono", ex);
            finishWithError("No se pudo iniciar el monitoreo: " + ex.getMessage(), 0);
            trackingInProgress.set(false);
            return false;
        }
    }

    @Scheduled(cron = "${tracking.schedule.cron:0 0 8 * * *}")
    public void scheduleDailyTracking() {
        log.info("Iniciando monitoreo diario programado de buques...");
        if (!startTrackingCycleAsync()) {
            log.warn("No se pudo iniciar el monitoreo diario: ya hay un ciclo en progreso.");
        }
    }

    public TrackingExecutionSummary runTrackingCycle() {
        if (!trackingInProgress.compareAndSet(false, true)) {
            log.warn("Se intentó iniciar un ciclo de monitoreo, pero ya hay uno en progreso.");
            return TrackingExecutionSummary.builder()
                    .totalProcessed(0)
                    .errorMessage("Ya existe un ciclo de monitoreo en progreso")
                    .build();
        }

        try {
            return doRunTrackingCycle();
        } finally {
            clearRunContext();
            trackingInProgress.set(false);
        }
    }

    private void runTrackingCycleInBackground() {
        long startTime = System.currentTimeMillis();
        try {
            doRunTrackingCycle();
        } catch (Exception ex) {
            log.error("Error inesperado en el ciclo de monitoreo asíncrono", ex);
            finishWithError("Error inesperado en el monitoreo: " + ex.getMessage(),
                    System.currentTimeMillis() - startTime);
        } finally {
            clearRunContext();
            trackingInProgress.set(false);
        }
    }

    private TrackingExecutionSummary doRunTrackingCycle() {
        long startTime = System.currentTimeMillis();
        List<Shipment> activeShipments;

        try {
            activeShipments = shipmentService.getActiveShipments();
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Error al obtener embarques activos: {}", ex.getMessage());
            finishWithError("Error al obtener embarques activos: " + ex.getMessage(), duration);
            return TrackingExecutionSummary.builder()
                    .totalProcessed(0)
                    .errorMessage("Error al obtener embarques activos: " + ex.getMessage())
                    .executionDurationMs(duration)
                    .build();
        }

        int total = activeShipments.size();
        int processed = 0;
        int updated = 0;
        int failed = 0;
        int alerts = 0;
        List<String> details = new ArrayList<>();

        startStatus(total);
        publishStart(total);
        log.info("Comenzando ciclo de monitoreo para {} embarques activos", total);

        boolean parallelCycle = parallelTrackingEnabled && carrierTrackingCoordinator != null;
        java.util.Map<Integer, java.util.concurrent.CompletableFuture<TrackingResult>> pilotResults = new java.util.HashMap<>();
        if (parallelCycle) {
            carrierTrackingCoordinator.beginCycle();
            for (int i = 0; i < total; i++) {
                Shipment candidate = activeShipments.get(i);
                if (manualCheckCarriers.contains(candidate.getCarrier())) continue;
                if ((candidate.getCarrier() == Carrier.MSC || candidate.getCarrier() == Carrier.MAERSK)
                        && activeShipments.subList(0, i).stream().noneMatch(previous -> previous.getCarrier() == candidate.getCarrier()
                        && !manualCheckCarriers.contains(previous.getCarrier()))) {
                    pilotResults.put(i, carrierTrackingCoordinator.submit(candidate.getCarrier(),
                            adapterFactory.getAdapter(candidate.getCarrier()), candidate.getTrackingNumber()));
                }
            }
        }
        try {
            for (int i = 0; i < total; i++) {
                Shipment shipment = activeShipments.get(i);
                setCurrentTrackingNumber(shipment.getTrackingNumber());

                try {
                    if (manualCheckCarriers.contains(shipment.getCarrier())) {
                        shipmentService.markForManualCheck(shipment);
                        details.add("Guía " + shipment.getTrackingNumber() + ": Omitida - verificar manual");
                        processed = i + 1;
                        updateProgress(total, processed, updated, failed, alerts);
                        continue;
                    }

                    CarrierTrackingAdapter adapter = adapterFactory.getAdapter(shipment.getCarrier());
                    TrackingResult result;
                    if (parallelCycle && (shipment.getCarrier() == Carrier.MSC || shipment.getCarrier() == Carrier.MAERSK)) {
                        // Wait for a bounded pilot lookup before advancing the ordered application loop.
                        result = pilotResults.get(i).get();
                    } else {
                        if (parallelCycle) carrierTrackingCoordinator.drain();
                        result = adapter.track(shipment.getTrackingNumber());
                    }

                    Shipment updatedShipment = shipmentService.updateFromTrackingResult(shipment, result);

                    if (result.isSuccess()) {
                        updated++;
                        details.add("Guía " + shipment.getTrackingNumber() + ": Actualizado - " + updatedShipment.getStatus());

                        boolean alertTriggered = checkAndSendAlerts(updatedShipment);
                        if (alertTriggered) {
                            alerts++;
                        }
                    } else {
                        failed++;
                        details.add("Guía " + shipment.getTrackingNumber() + ": Error - " + result.getErrorMessage());
                    }
                } catch (Exception ex) {
                    failed++;
                    log.error("Error al procesar rastreo de guía {}: {}", shipment.getTrackingNumber(), ex.getMessage());
                    details.add("Guía " + shipment.getTrackingNumber() + ": Fallo inesperado - " + ex.getMessage());
                }

                processed = i + 1;
                updateProgress(total, processed, updated, failed, alerts);

                // Pausa de cortesía entre peticiones para evitar bloqueos por rate-limiting
                if (delayMs > 0 && i < total - 1) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Monitoreo interrumpido durante pausa de cortesía");
                        break;
                    }
                }
            }
        } finally {
            if (parallelCycle) {
                try { carrierTrackingCoordinator.drain(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            try {
                adapterFactory.releaseResources();
            } catch (Exception ex) {
                log.warn("No se pudieron liberar los recursos de los adaptadores de rastreo: {}", ex.getMessage());
            }
            long duration = System.currentTimeMillis() - startTime;
            finishStatus(processed, total, updated, failed, alerts, duration, details);
        }

        purgeExpiredShipments();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Ciclo de monitoreo finalizado. Procesados: {}, Actualizados: {}, Fallidos: {}, Alertas enviadas: {} en {} ms",
                processed, updated, failed, alerts, duration);

        return TrackingExecutionSummary.builder()
                .totalProcessed(total)
                .updatedCount(updated)
                .failedCount(failed)
                .alertsSentCount(alerts)
                .executionDurationMs(duration)
                .details(details)
                .build();
    }

    /**
     * La limpieza nunca debe tumbar el ciclo: si falla, se registra y el
     * monitoreo se reporta igual.
     */
    private void purgeExpiredShipments() {
        if (retentionService == null) {
            return;
        }
        try {
            retentionService.purgeExpired(LocalDateTime.now());
        } catch (Exception ex) {
            log.warn("No se pudo ejecutar la limpieza de retención: {}", ex.getMessage());
        }
    }

    private synchronized void markQueued() {
        trackingStatus = TrackingStatus.builder()
                .running(true)
                .message("Preparando monitoreo...")
                .startedAt(LocalDateTime.now())
                .details(new ArrayList<>())
                .build();
    }

    private synchronized void startStatus(int total) {
        trackingStatus = TrackingStatus.builder()
                .running(true)
                .total(total)
                .message("Monitoreando...")
                .startedAt(LocalDateTime.now())
                .details(new ArrayList<>())
                .build();
    }

    private synchronized void setCurrentTrackingNumber(String trackingNumber) {
        trackingStatus.setCurrentTrackingNumber(trackingNumber);
    }

    private synchronized void updateProgress(int total, int processed, int updated, int failed, int alerts) {
        trackingStatus.setProcessed(processed);
        trackingStatus.setUpdated(updated);
        trackingStatus.setFailed(failed);
        trackingStatus.setAlertsSent(alerts);
        publishUpdate(total, processed, updated, failed);
    }

    private synchronized void finishStatus(int processed, int total, int updated, int failed, int alerts,
                                           long durationMs, List<String> details) {
        trackingStatus.setRunning(false);
        trackingStatus.setProcessed(processed);
        trackingStatus.setTotal(total);
        trackingStatus.setUpdated(updated);
        trackingStatus.setFailed(failed);
        trackingStatus.setAlertsSent(alerts);
        trackingStatus.setCurrentTrackingNumber(null);
        trackingStatus.setFinishedAt(LocalDateTime.now());
        trackingStatus.setDurationMs(durationMs);
        trackingStatus.setMessage(failed > 0 ? "Monitoreo finalizado con fallos" : "Monitoreo finalizado");
        trackingStatus.setDetails(new ArrayList<>(details));
        publishComplete(processed, total, updated, failed);
    }

    private synchronized void finishWithError(String message, long durationMs) {
        trackingStatus.setRunning(false);
        trackingStatus.setCurrentTrackingNumber(null);
        trackingStatus.setFinishedAt(LocalDateTime.now());
        trackingStatus.setDurationMs(durationMs);
        trackingStatus.setMessage(message);
        publishFail(message);
    }

    private void publishStart(int total) {
        if (progressService == null || runId == null) {
            return;
        }
        try {
            progressService.start(runId, origin);
            progressService.update(runId, total, 0, 0, 0);
        } catch (Exception ex) {
            log.warn("No se pudo publicar el inicio de la ejecución {}: {}", runId, ex.getMessage());
        }
    }

    private void publishUpdate(int total, int processed, int updated, int failed) {
        if (progressService == null || runId == null) {
            return;
        }
        try {
            progressService.update(runId, total, processed, updated, failed);
        } catch (Exception ex) {
            log.warn("No se pudo publicar el avance de la ejecución {}: {}", runId, ex.getMessage());
        }
    }

    private void publishComplete(int processed, int total, int updated, int failed) {
        if (progressService == null || runId == null) {
            return;
        }
        try {
            progressService.complete(runId, total, processed, updated, failed);
        } catch (Exception ex) {
            log.warn("No se pudo publicar el fin de la ejecución {}: {}", runId, ex.getMessage());
        }
    }

    private void publishFail(String message) {
        if (progressService == null || runId == null) {
            return;
        }
        try {
            progressService.fail(runId, message);
        } catch (Exception ex) {
            log.warn("No se pudo publicar el fallo de la ejecución {}: {}", runId, ex.getMessage());
        }
    }

    private boolean checkAndSendAlerts(Shipment shipment) {
        boolean sent = false;

        // Caso 1: Arribo próximo (<= 3 días)
        if (shipment.getStatus() == ShipmentStatus.NEAR_ARRIVAL && !shipment.isAlertSent3Days()) {
            Long days = shipmentService.calculateDaysRemaining(shipment, LocalDate.now());
            long daysRemaining = days != null ? days : 0;
            boolean tgSent = telegramNotificationService.sendNearArrivalAlert(shipment, daysRemaining);
            boolean dcSent = discordNotificationService.sendNearArrivalAlert(shipment, daysRemaining);
            if (tgSent || dcSent) {
                shipment.setAlertSent3Days(true);
                shipmentRepository.save(shipment);
                sent = true;
            }
        }

        // Caso 2: Buque arribó a puerto
        if (shipment.getStatus() == ShipmentStatus.ARRIVED && !shipment.isAlertSentArrived()) {
            if (isHistoricalArrival(shipment)) {
                // El arribo ocurrió antes de registrar el embarque (por ejemplo,
                // guías históricas cargadas por Excel). No se notifica.
                log.info("Arribo histórico para guía {}: arribo {} anterior al registro {}; se omite la notificación.",
                        shipment.getTrackingNumber(), shipment.getActualArrival(), shipment.getCreatedAt());
                shipment.setAlertSentArrived(true);
                shipmentRepository.save(shipment);
            } else {
                boolean tgSent = telegramNotificationService.sendArrivedAlert(shipment);
                boolean dcSent = discordNotificationService.sendArrivedAlert(shipment);
                if (tgSent || dcSent) {
                    shipment.setAlertSentArrived(true);
                    shipmentRepository.save(shipment);
                    sent = true;
                }
            }
        }

        return sent;
    }

    /**
     * Un arribo es histórico cuando ocurrió antes de que el embarque fuera
     * registrado en el sistema. En ese caso no debe generar una notificación
     * retroactiva (típico de guías ya arribadas importadas por Excel).
     */
    private boolean isHistoricalArrival(Shipment shipment) {
        LocalDateTime actualArrival = shipment.getActualArrival();
        LocalDateTime createdAt = shipment.getCreatedAt();
        return actualArrival != null && createdAt != null && actualArrival.isBefore(createdAt);
    }
}
