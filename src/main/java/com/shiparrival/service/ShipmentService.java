package com.shiparrival.service;

import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.BatchImportSummary;
import com.shiparrival.service.dto.ExcelImportResult;
import com.shiparrival.service.dto.ExcelShipmentRow;
import com.shiparrival.service.dto.ShipmentStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private static final int MAX_EVENT_DESCRIPTION_LENGTH = 512;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventLogRepository shipmentEventLogRepository;
    private final ExcelImportService excelImportService;

    @Transactional(readOnly = true)
    public boolean existsByTrackingNumber(String trackingNumber) {
        String cleanTrackingNumber = trackingNumber != null ? trackingNumber.trim() : "";
        return !cleanTrackingNumber.isEmpty()
                && shipmentRepository.findByTrackingNumber(cleanTrackingNumber).isPresent();
    }

    @Transactional
    public Shipment registerShipment(String trackingNumber, Carrier carrier) {
        String cleanTrackingNumber = trackingNumber != null ? trackingNumber.trim() : "";
        if (cleanTrackingNumber.isEmpty()) {
            throw new IllegalArgumentException("El número de guía no puede estar vacío");
        }

        Optional<Shipment> existing = shipmentRepository.findByTrackingNumber(cleanTrackingNumber);
        if (existing.isPresent()) {
            log.info("Guía {} ya existe en el sistema, omitiendo duplicado", cleanTrackingNumber);
            return existing.get();
        }

        Shipment shipment = Shipment.builder()
                .trackingNumber(cleanTrackingNumber)
                .carrier(carrier != null ? carrier : Carrier.ONE)
                .status(ShipmentStatus.PENDING_FIRST_CHECK)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .build();

        Shipment saved = shipmentRepository.save(shipment);

        ShipmentEventLog logEntry = ShipmentEventLog.builder()
                .shipment(saved)
                .eventType("SHIPMENT_REGISTERED")
                .description("Embarque registrado en el sistema")
                .recordedAt(LocalDateTime.now())
                .build();
        shipmentEventLogRepository.save(logEntry);

        return saved;
    }

    @Transactional
    public BatchImportSummary importFromExcel(InputStream inputStream, Carrier defaultCarrier) {
        ExcelImportResult excelResult = excelImportService.extractTrackingNumbers(inputStream);
        List<String> added = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();

        for (ExcelShipmentRow row : excelResult.getValidRows()) {
            String trackingNumber = row.getTrackingNumber();
            Carrier carrier = row.getCarrier() != null
                    ? row.getCarrier()
                    : (defaultCarrier != null ? defaultCarrier : Carrier.ONE);

            Optional<Shipment> existing = shipmentRepository.findByTrackingNumber(trackingNumber);
            if (existing.isPresent()) {
                duplicates.add(trackingNumber);
            } else {
                Shipment shipment = Shipment.builder()
                        .trackingNumber(trackingNumber)
                        .carrier(carrier)
                        .status(ShipmentStatus.PENDING_FIRST_CHECK)
                        .active(true)
                        .alertSent3Days(false)
                        .alertSentArrived(false)
                        .build();
                shipmentRepository.save(shipment);
                added.add(trackingNumber);

                ShipmentEventLog logEntry = ShipmentEventLog.builder()
                        .shipment(shipment)
                        .eventType("EXCEL_IMPORTED")
                        .description("Importado vía archivo Excel")
                        .recordedAt(LocalDateTime.now())
                        .build();
                shipmentEventLogRepository.save(logEntry);
            }
        }

        return BatchImportSummary.builder()
                .totalProcessed(excelResult.getValidRows().size())
                .addedCount(added.size())
                .duplicateCount(duplicates.size())
                .addedTrackingNumbers(added)
                .duplicateTrackingNumbers(duplicates)
                .errorMessages(excelResult.getInvalidRows())
                .build();
    }

    /**
     * Marca un embarque para que un operador consulte la naviera a mano. Se usa
     * con las navieras cuyo rastreo automático está deshabilitado. No modifica
     * {@code lastCheckedAt} porque no hubo consulta real, y respeta los estados
     * finales (arribado o cancelado) para no degradar guías ya resueltas.
     */
    @Transactional
    public Shipment markForManualCheck(Shipment shipment) {
        if (shipment.getStatus() == ShipmentStatus.ARRIVED
                || shipment.getStatus() == ShipmentStatus.CANCELLED
                || shipment.getStatus() == ShipmentStatus.MANUAL_CHECK) {
            return shipment;
        }
        shipment.setStatus(ShipmentStatus.MANUAL_CHECK);
        log.info("Embarque marcado para verificación manual: Guía={}", shipment.getTrackingNumber());
        return shipmentRepository.save(shipment);
    }

    @Transactional
    private String limitEventDescription(String description) {
        if (description == null || description.length() <= MAX_EVENT_DESCRIPTION_LENGTH) {
            return description;
        }
        return description.substring(0, MAX_EVENT_DESCRIPTION_LENGTH);
    }

    public Shipment updateFromTrackingResult(Shipment shipment, TrackingResult result) {
        shipment.setLastCheckedAt(LocalDateTime.now());

        if (!result.isSuccess()) {
            shipment.setStatus(ShipmentStatus.ERROR_CHECKING);
            ShipmentEventLog errorLog = ShipmentEventLog.builder()
                    .shipment(shipment)
                    .eventType("SCRAPE_FAILED")
                    .description(limitEventDescription("Error al rastrear: " + result.getErrorMessage()))
                    .recordedAt(LocalDateTime.now())
                    .build();
            shipmentEventLogRepository.save(errorLog);
            return shipmentRepository.save(shipment);
        }

        // Actualizar datos del buque y contenedor
        if (result.getContainerNumber() != null) {
            shipment.setContainerNumber(result.getContainerNumber());
        }
        if (result.getVesselName() != null) {
            shipment.setVesselName(result.getVesselName());
        }
        if (result.getVoyageNumber() != null) {
            shipment.setVoyageNumber(result.getVoyageNumber());
        }
        if (result.getPortOfLoading() != null) {
            shipment.setPortOfLoading(result.getPortOfLoading());
        }
        if (result.getPortOfDischarge() != null) {
            shipment.setPortOfDischarge(result.getPortOfDischarge());
        }
        if (result.getDepartureDate() != null) {
            shipment.setDepartureDate(result.getDepartureDate());
        }
        if (result.getActualArrival() != null) {
            shipment.setActualArrival(result.getActualArrival());
        }

        // Revisar si hubo cambio de ETA
        LocalDateTime oldEta = shipment.getEstimatedArrival();
        LocalDateTime newEta = result.getEstimatedArrival();
        boolean etaChanged = (oldEta == null && newEta != null) ||
                             (oldEta != null && newEta != null && !oldEta.isEqual(newEta));

        if (etaChanged) {
            shipment.setEstimatedArrival(newEta);
            ShipmentEventLog etaLog = ShipmentEventLog.builder()
                    .shipment(shipment)
                    .eventType("ETA_UPDATED")
                    .previousEta(oldEta)
                    .newEta(newEta)
                    .description("Fecha estimada de arribo actualizada")
                    .recordedAt(LocalDateTime.now())
                    .build();
            shipmentEventLogRepository.save(etaLog);
        }

        // Determinar estatus final
        if (result.getDetectedStatus() == ShipmentStatus.ARRIVED) {
            shipment.setStatus(ShipmentStatus.ARRIVED);
            shipment.setActive(false);
        } else if (checkProximity(shipment, LocalDate.now())) {
            shipment.setStatus(ShipmentStatus.NEAR_ARRIVAL);
        } else {
            shipment.setStatus(result.getDetectedStatus() != null ? result.getDetectedStatus() : ShipmentStatus.IN_TRANSIT);
        }

        return shipmentRepository.save(shipment);
    }

    public boolean checkProximity(Shipment shipment, LocalDate referenceDate) {
        if (shipment.getEstimatedArrival() == null || shipment.getStatus() == ShipmentStatus.ARRIVED) {
            return false;
        }
        LocalDate etaDate = shipment.getEstimatedArrival().toLocalDate();
        long days = ChronoUnit.DAYS.between(referenceDate, etaDate);
        return days >= 0 && days <= 3;
    }

    public Long calculateDaysRemaining(Shipment shipment, LocalDate referenceDate) {
        if (shipment.getEstimatedArrival() == null) {
            return null;
        }
        LocalDate etaDate = shipment.getEstimatedArrival().toLocalDate();
        return ChronoUnit.DAYS.between(referenceDate, etaDate);
    }

    @Transactional(readOnly = true)
    public List<Shipment> getAllShipments() {
        return shipmentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Shipment> getActiveShipments() {
        return shipmentRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> getShipmentById(Long id) {
        return shipmentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ShipmentEventLog> getEventLogs(Long shipmentId) {
        return shipmentEventLogRepository.findByShipmentIdOrderByRecordedAtDesc(shipmentId);
    }

    @Transactional(readOnly = true)
    public List<Shipment> searchShipments(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return getAllShipments();
        }
        return shipmentRepository.findAll().stream()
                .filter(s -> s.getTrackingNumber().toLowerCase().contains(searchTerm.toLowerCase()) ||
                           (s.getContainerNumber() != null && s.getContainerNumber().toLowerCase().contains(searchTerm.toLowerCase())) ||
                           (s.getVesselName() != null && s.getVesselName().toLowerCase().contains(searchTerm.toLowerCase())))
                .toList();
    }

    /**
     * Obtiene los embarques visibles en el dashboard de forma paginada.
     * Los embarques arribados hace más de 3 días se omiten automáticamente
     * (quedan archivados en la base de datos).
     */
    @Transactional(readOnly = true)
    public Page<Shipment> getVisibleShipments(String searchTerm, Pageable pageable) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        return shipmentRepository.findVisibleShipments(
                cutoff, ShipmentStatus.ARRIVED, toSearchPattern(searchTerm), pageable);
    }

    /**
     * Calcula las métricas del dashboard sobre el universo de embarques visibles
     * (no solo la página actual), respetando el filtro de búsqueda.
     */
    @Transactional(readOnly = true)
    public ShipmentStats getVisibleShipmentStats(String searchTerm) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        List<Object[]> rows = shipmentRepository.countVisibleByStatus(
                cutoff, ShipmentStatus.ARRIVED, toSearchPattern(searchTerm));

        long total = 0;
        long inTransit = 0;
        long nearArrival = 0;
        long arrived = 0;

        for (Object[] row : rows) {
            ShipmentStatus status = (ShipmentStatus) row[0];
            long count = ((Number) row[1]).longValue();
            total += count;
            switch (status) {
                case IN_TRANSIT -> inTransit += count;
                case NEAR_ARRIVAL -> nearArrival += count;
                case ARRIVED -> arrived += count;
                default -> {
                    // PENDING_FIRST_CHECK, ERROR_CHECKING y CANCELLED solo suman al total
                }
            }
        }

        return new ShipmentStats(total, inTransit, nearArrival, arrived);
    }

    private String toSearchPattern(String searchTerm) {
        return (searchTerm == null || searchTerm.isBlank())
                ? "%"
                : "%" + searchTerm.trim().toLowerCase() + "%";
    }

    @Transactional
    public void deleteShipment(Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Embarque no encontrado con ID: " + id));
        
        // Primero eliminar los logs relacionados
        shipmentEventLogRepository.deleteByShipmentId(id);
        
        // Luego eliminar el embarque
        shipmentRepository.delete(shipment);
        log.info("Embarque eliminado: ID={}, Guía={}", id, shipment.getTrackingNumber());
    }
}
