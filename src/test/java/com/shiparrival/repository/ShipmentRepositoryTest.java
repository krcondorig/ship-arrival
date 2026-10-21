package com.shiparrival.repository;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ShipmentRepositoryTest {

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private ShipmentEventLogRepository shipmentEventLogRepository;

    @Test
    @DisplayName("Debe persistir y buscar un Shipment por número de guía y filtrar por activos")
    void shouldSaveAndFindShipment() {
        Shipment shipment = Shipment.builder()
                .trackingNumber("TAOGR4856400")
                .carrier(Carrier.ONE)
                .containerNumber("TCNU1234567")
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfLoading("SHANGHAI, CHINA")
                .portOfDischarge("MANZANILLO, MEXICO")
                .departureDate(LocalDateTime.of(2026, 9, 20, 13, 6))
                .estimatedArrival(LocalDateTime.of(2026, 10, 20, 9, 0))
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Shipment saved = shipmentRepository.save(shipment);
        assertThat(saved.getId()).isNotNull();

        Optional<Shipment> foundByTracking = shipmentRepository.findByTrackingNumber("TAOGR4856400");
        assertThat(foundByTracking).isPresent();
        assertThat(foundByTracking.get().getVesselName()).isEqualTo("ONE FANTASTIC");
        assertThat(foundByTracking.get().getCarrier()).isEqualTo(Carrier.ONE);

        List<Shipment> activeShipments = shipmentRepository.findByActiveTrue();
        assertThat(activeShipments).hasSize(1);

        // Asociar un log de evento
        ShipmentEventLog log = ShipmentEventLog.builder()
                .shipment(saved)
                .eventType("ETA_UPDATED")
                .previousEta(LocalDateTime.of(2026, 10, 18, 9, 0))
                .newEta(LocalDateTime.of(2026, 10, 20, 9, 0))
                .description("ETA reprogramado por naviera ONE")
                .recordedAt(LocalDateTime.now())
                .build();

        shipmentEventLogRepository.save(log);

        List<ShipmentEventLog> logs = shipmentEventLogRepository.findByShipmentIdOrderByRecordedAtDesc(saved.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getEventType()).isEqualTo("ETA_UPDATED");
    }
}
