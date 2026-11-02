package com.shiparrival.config;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentDataReactivationMigrationTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private ShipmentEventLogRepository shipmentEventLogRepository;

    @InjectMocks
    private ShipmentDataReactivationMigration migration;

    @Test
    @DisplayName("Debe reactivar embarques con falso arribo y ETA futuro")
    void shouldReactivateFalseArrivedShipmentsWithFutureEta() {
        Shipment shipment = Shipment.builder()
                .id(10L)
                .trackingNumber("TA6DB5606900")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.ARRIVED)
                .active(false)
                .actualArrival(LocalDateTime.of(2026, 11, 2, 5, 8))
                .alertSentArrived(true)
                .estimatedArrival(LocalDateTime.now().plusDays(25))
                .build();

        when(shipmentRepository.findFalseArrivedShipments(eq(ShipmentStatus.ARRIVED), any(LocalDateTime.class)))
                .thenReturn(List.of(shipment));

        migration.run(new DefaultApplicationArguments(new String[0]));

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(shipment.isActive()).isTrue();
        assertThat(shipment.getActualArrival()).isNull();
        assertThat(shipment.isAlertSentArrived()).isFalse();

        verify(shipmentRepository).save(shipment);

        ArgumentCaptor<ShipmentEventLog> logCaptor = ArgumentCaptor.forClass(ShipmentEventLog.class);
        verify(shipmentEventLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getEventType()).isEqualTo("AUTO_REACTIVATED");
        assertThat(logCaptor.getValue().getShipment()).isEqualTo(shipment);
    }

    @Test
    @DisplayName("No debe hacer nada si no hay embarques con falso arribo")
    void shouldDoNothingWhenNoFalseArrivedShipments() {
        when(shipmentRepository.findFalseArrivedShipments(eq(ShipmentStatus.ARRIVED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(shipmentRepository, never()).save(any());
        verify(shipmentEventLogRepository, never()).save(any());
    }
}
