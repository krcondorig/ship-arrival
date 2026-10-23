package com.shiparrival.repository;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .containerNumber("TEST1234567")
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

        Optional<Shipment> foundByTracking = shipmentRepository.findByTrackingNumber("TESTBL900001");
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

    @Test
    @DisplayName("Debe ocultar embarques arribados hace más de 3 días y mantener los recientes")
    void shouldHideOldArrivedShipments() {
        LocalDateTime now = LocalDateTime.now();

        shipmentRepository.save(Shipment.builder()
                .trackingNumber("ACTIVE1")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .build());

        shipmentRepository.save(Shipment.builder()
                .trackingNumber("ARRIVED_RECENT")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.ARRIVED)
                .active(false)
                .actualArrival(now.minusDays(1))
                .build());

        shipmentRepository.save(Shipment.builder()
                .trackingNumber("ARRIVED_OLD")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.ARRIVED)
                .active(false)
                .actualArrival(now.minusDays(5))
                .build());

        // Dato legado: arribado antiguo que quedó marcado como activo.
        // Debe ocultarse igualmente por su fecha de arribo.
        shipmentRepository.save(Shipment.builder()
                .trackingNumber("ARRIVED_OLD_ACTIVE")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.ARRIVED)
                .active(true)
                .actualArrival(now.minusDays(10))
                .build());

        Page<Shipment> visible = shipmentRepository.findVisibleShipments(
                now.minusDays(3), ShipmentStatus.ARRIVED, "%", PageRequest.of(0, 10));

        assertThat(visible.getContent())
                .extracting(Shipment::getTrackingNumber)
                .containsExactlyInAnyOrder("ACTIVE1", "ARRIVED_RECENT");
    }
}
