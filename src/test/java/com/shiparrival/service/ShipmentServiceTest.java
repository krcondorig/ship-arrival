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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private ShipmentEventLogRepository shipmentEventLogRepository;

    @Mock
    private ExcelImportService excelImportService;

    private ShipmentService shipmentService;

    @BeforeEach
    void setUp() {
        shipmentService = new ShipmentService(shipmentRepository, shipmentEventLogRepository, excelImportService);
    }

    @Test
    @DisplayName("Debe registrar un nuevo embarque y no duplicar si ya existe")
    void shouldRegisterNewShipmentAndAvoidDuplicates() {
        when(shipmentRepository.findByTrackingNumber("TESTBL900001")).thenReturn(Optional.empty());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        Shipment created = shipmentService.registerShipment("TESTBL900001", Carrier.ONE);
        assertThat(created).isNotNull();
        assertThat(created.getTrackingNumber()).isEqualTo("TESTBL900001");
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.PENDING_FIRST_CHECK);

        // Registro de una guía ya existente
        when(shipmentRepository.findByTrackingNumber("TESTBL900001")).thenReturn(Optional.of(created));
        Shipment existing = shipmentService.registerShipment("TESTBL900001", Carrier.ONE);
        assertThat(existing.getId()).isEqualTo(1L);
        verify(shipmentRepository, times(1)).save(any(Shipment.class));
    }

    @Test
    @DisplayName("Debe importar lote desde Excel y reportar nuevas y duplicadas")
    void shouldImportFromExcelAndSummarize() {
        InputStream mockStream = mock(InputStream.class);
        ExcelImportResult excelResult = ExcelImportResult.builder()
                .validTrackingNumbers(List.of("GUIA1", "GUIA2", "GUIA3"))
                .invalidRows(List.of())
                .totalRowsProcessed(3)
                .build();

        when(excelImportService.extractTrackingNumbers(mockStream)).thenReturn(excelResult);
        when(shipmentRepository.findByTrackingNumber("GUIA1")).thenReturn(Optional.empty());
        when(shipmentRepository.findByTrackingNumber("GUIA2")).thenReturn(Optional.empty());
        when(shipmentRepository.findByTrackingNumber("GUIA3")).thenReturn(Optional.of(new Shipment()));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchImportSummary summary = shipmentService.importFromExcel(mockStream, Carrier.ONE);

        assertThat(summary.getTotalProcessed()).isEqualTo(3);
        assertThat(summary.getAddedCount()).isEqualTo(2);
        assertThat(summary.getDuplicateCount()).isEqualTo(1);
        assertThat(summary.getAddedTrackingNumbers()).containsExactly("GUIA1", "GUIA2");
    }

    @Test
    @DisplayName("Debe detectar correctamente la regla de proximidad (<= 3 días)")
    void shouldDetectProximityWithin3Days() {
        LocalDate refDate = LocalDate.of(2026, 10, 17);

        // 3 días de diferencia (17 al 20) -> true
        Shipment s3 = Shipment.builder()
                .estimatedArrival(LocalDateTime.of(2026, 10, 20, 9, 0))
                .status(ShipmentStatus.IN_TRANSIT)
                .build();
        assertThat(shipmentService.checkProximity(s3, refDate)).isTrue();

        // 2 días de diferencia (17 al 19) -> true
        Shipment s2 = Shipment.builder()
                .estimatedArrival(LocalDateTime.of(2026, 10, 19, 9, 0))
                .status(ShipmentStatus.IN_TRANSIT)
                .build();
        assertThat(shipmentService.checkProximity(s2, refDate)).isTrue();

        // 10 días de diferencia (17 al 27) -> false
        Shipment s10 = Shipment.builder()
                .estimatedArrival(LocalDateTime.of(2026, 10, 27, 9, 0))
                .status(ShipmentStatus.IN_TRANSIT)
                .build();
        assertThat(shipmentService.checkProximity(s10, refDate)).isFalse();
    }

    @Test
    @DisplayName("Debe actualizar el embarque con el resultado de rastreo y registrar cambio de ETA en bitácora")
    void shouldUpdateShipmentAndLogEtaChange() {
        Shipment shipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .estimatedArrival(LocalDateTime.of(2026, 10, 15, 9, 0))
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .build();

        TrackingResult result = TrackingResult.builder()
                .success(true)
                .trackingNumber("TESTBL900001")
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfLoading("SHANGHAI, CHINA")
                .portOfDischarge("MANZANILLO, MEXICO")
                .departureDate(LocalDateTime.of(2026, 9, 20, 13, 6))
                .estimatedArrival(LocalDateTime.of(2026, 10, 20, 9, 0)) // Nuevo ETA
                .detectedStatus(ShipmentStatus.IN_TRANSIT)
                .build();

        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        Shipment updated = shipmentService.updateFromTrackingResult(shipment, result);

        assertThat(updated.getVesselName()).isEqualTo("ONE FANTASTIC");
        assertThat(updated.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
        assertThat(updated.getLastCheckedAt()).isNotNull();

        // Debe haber guardado el log de cambio de ETA
        verify(shipmentEventLogRepository, times(1)).save(any(ShipmentEventLog.class));
    }
}
