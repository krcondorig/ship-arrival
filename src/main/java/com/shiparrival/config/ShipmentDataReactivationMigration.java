package com.shiparrival.config;

import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Corrige y reactiva al iniciar la aplicación los embarques que quedaron erróneamente
 * marcados como {@code ARRIVED} pero cuya fecha estimada de arribo (ETA) todavía se
 * encuentra en el futuro (típico de eventos de transbordo en puertos intermedios).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ShipmentDataReactivationMigration implements ApplicationRunner {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventLogRepository shipmentEventLogRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Shipment> falseArrived = shipmentRepository.findFalseArrivedShipments(ShipmentStatus.ARRIVED, now);
            if (falseArrived.isEmpty()) {
                return;
            }

            for (Shipment shipment : falseArrived) {
                shipment.setStatus(ShipmentStatus.IN_TRANSIT);
                shipment.setActive(true);
                shipment.setActualArrival(null);
                shipment.setAlertSentArrived(false);
                shipmentRepository.save(shipment);

                ShipmentEventLog logEntry = ShipmentEventLog.builder()
                        .shipment(shipment)
                        .eventType("AUTO_REACTIVATED")
                        .description("Reactivado automáticamente a En Tránsito: arribo en puerto intermedio (transbordo) con ETA futuro")
                        .recordedAt(now)
                        .build();
                shipmentEventLogRepository.save(logEntry);

                log.info("Embarque {} reactivado automáticamente a IN_TRANSIT (ETA: {})",
                        shipment.getTrackingNumber(), shipment.getEstimatedArrival());
            }
        } catch (Exception ex) {
            log.warn("No se pudo ejecutar la migración de auto-recuperación de embarques: {}", ex.getMessage());
        }
    }
}
