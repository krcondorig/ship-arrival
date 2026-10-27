package com.shiparrival.service;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention;DB_CLOSE_DELAY=-1",
        "app.auth.enabled=false",
        "tracking.retention.days=7"
})
class ShipmentRetentionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Autowired
    private ShipmentRetentionService retentionService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private ShipmentEventLogRepository eventLogRepository;

    @BeforeEach
    void cleanDatabase() {
        eventLogRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    private Shipment save(String trackingNumber,
                          ShipmentStatus status,
                          LocalDateTime actualArrival,
                          LocalDateTime estimatedArrival,
                          LocalDateTime updatedAt) {
        return shipmentRepository.save(Shipment.builder()
                .trackingNumber(trackingNumber)
                .carrier(Carrier.ONE)
                .status(status)
                .active(status != ShipmentStatus.ARRIVED)
                .actualArrival(actualArrival)
                .estimatedArrival(estimatedArrival)
                .updatedAt(updatedAt)
                .build());
    }

    @Test
    @DisplayName("Borra el embarque arribado hace más de 7 días y conserva el reciente")
    void shouldDeleteArrivedShipmentOlderThanRetention() {
        Shipment old = save("OLD-ARRIVED", ShipmentStatus.ARRIVED,
                NOW.minusDays(8), null, NOW.minusDays(8));
        save("RECENT-ARRIVED", ShipmentStatus.ARRIVED,
                NOW.minusDays(6), null, NOW.minusDays(6));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(shipmentRepository.findById(old.getId())).isEmpty();
        assertThat(shipmentRepository.findByTrackingNumber("RECENT-ARRIVED")).isPresent();
    }

    @Test
    @DisplayName("Al borrar el embarque se lleva su historial")
    void shouldDeleteEventLogsWithTheShipment() {
        Shipment old = save("OLD-WITH-LOGS", ShipmentStatus.ARRIVED,
                NOW.minusDays(10), null, NOW.minusDays(10));
        eventLogRepository.save(ShipmentEventLog.builder()
                .shipment(old)
                .eventType("ETA_UPDATED")
                .description("prueba")
                .recordedAt(NOW.minusDays(10))
                .build());

        retentionService.purgeExpired(NOW);

        assertThat(eventLogRepository.findByShipmentIdOrderByRecordedAtDesc(old.getId())).isEmpty();
    }

    @Test
    @DisplayName("Borra el embarque cancelado hace más de 7 días")
    void shouldDeleteCancelledShipmentOlderThanRetention() {
        Shipment old = save("OLD-CANCELLED", ShipmentStatus.CANCELLED,
                null, null, NOW.minusDays(8));
        save("RECENT-CANCELLED", ShipmentStatus.CANCELLED, null, null, NOW.minusDays(6));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(shipmentRepository.findById(old.getId())).isEmpty();
        assertThat(shipmentRepository.findByTrackingNumber("RECENT-CANCELLED")).isPresent();
    }

    @Test
    @DisplayName("Borra el embarque con error cuya ETA ya venció hace más de 7 días")
    void shouldDeleteErroredShipmentWhoseEtaIsLongPast() {
        Shipment old = save("OLD-ERROR", ShipmentStatus.ERROR_CHECKING,
                null, NOW.minusDays(8), NOW.minusDays(1));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(shipmentRepository.findById(old.getId())).isEmpty();
    }

    @Test
    @DisplayName("Conserva el embarque con error si su ETA todavía no venció")
    void shouldKeepErroredShipmentWithFutureEta() {
        save("ERROR-FUTURE-ETA", ShipmentStatus.ERROR_CHECKING,
                null, NOW.plusDays(5), NOW.minusDays(1));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(shipmentRepository.findByTrackingNumber("ERROR-FUTURE-ETA")).isPresent();
    }

    @Test
    @DisplayName("Conserva el embarque con error sin ETA: no hay forma de saber que está muerto")
    void shouldKeepErroredShipmentWithoutEta() {
        save("ERROR-NO-ETA", ShipmentStatus.ERROR_CHECKING, null, null, NOW.minusDays(30));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(shipmentRepository.findByTrackingNumber("ERROR-NO-ETA")).isPresent();
    }

    @Test
    @DisplayName("No toca los embarques en tránsito ni los pendientes")
    void shouldKeepInTransitShipments() {
        save("IN-TRANSIT", ShipmentStatus.IN_TRANSIT, null, NOW.minusDays(30), NOW.minusDays(30));
        save("PENDING", ShipmentStatus.PENDING_FIRST_CHECK, null, null, NOW.minusDays(30));

        int deleted = retentionService.purgeExpired(NOW);

        assertThat(deleted).isZero();
    }
}
