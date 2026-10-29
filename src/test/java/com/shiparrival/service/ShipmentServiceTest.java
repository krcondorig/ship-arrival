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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
    @DisplayName("Debe indicar si una guía ya está registrada")
    void shouldReportWhetherTrackingNumberExists() {
        when(shipmentRepository.findByTrackingNumber("TESTBL900001"))
                .thenReturn(Optional.of(new Shipment()));
        when(shipmentRepository.findByTrackingNumber("TESTBL900002"))
                .thenReturn(Optional.empty());

        assertThat(shipmentService.existsByTrackingNumber("TESTBL900001")).isTrue();
        assertThat(shipmentService.existsByTrackingNumber("TESTBL900002")).isFalse();
    }

    @Test
    @DisplayName("Debe importar lote desde Excel y reportar nuevas y duplicadas")
    void shouldImportFromExcelAndSummarize() {
        InputStream mockStream = mock(InputStream.class);
        ExcelImportResult excelResult = ExcelImportResult.builder()
                .validRows(List.of(
                        ExcelShipmentRow.builder().trackingNumber("GUIA1").carrier(Carrier.MSC).build(),
                        ExcelShipmentRow.builder().trackingNumber("GUIA2").build(),
                        ExcelShipmentRow.builder().trackingNumber("GUIA3").carrier(Carrier.ONE).build()))
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

        ArgumentCaptor<Shipment> shipmentCaptor = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepository, times(2)).save(shipmentCaptor.capture());
        assertThat(shipmentCaptor.getAllValues())
                .extracting(Shipment::getCarrier)
                .containsExactly(Carrier.MSC, Carrier.ONE);
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

    @Test
    @DisplayName("Debe consultar embarques visibles con paginación y patrón de búsqueda")
    void shouldQueryVisibleShipmentsWithPagination() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Shipment> page = new PageImpl<>(List.of(new Shipment()));
        when(shipmentRepository.findVisibleShipments(
                any(LocalDateTime.class), eq(ShipmentStatus.ARRIVED), eq("%test%"), eq(pageable)))
                .thenReturn(page);

        Page<Shipment> result = shipmentService.getVisibleShipments("TEST", pageable);

        assertThat(result).isSameAs(page);
        verify(shipmentRepository).findVisibleShipments(
                any(LocalDateTime.class), eq(ShipmentStatus.ARRIVED), eq("%test%"), eq(pageable));
    }

    @Test
    @DisplayName("Debe calcular las estadísticas de los embarques visibles")
    void shouldCalculateVisibleShipmentStats() {
        when(shipmentRepository.countVisibleByStatus(
                any(LocalDateTime.class), eq(ShipmentStatus.ARRIVED), eq("%")))
                .thenReturn(List.of(
                        new Object[]{ShipmentStatus.IN_TRANSIT, 5L},
                        new Object[]{ShipmentStatus.NEAR_ARRIVAL, 2L},
                        new Object[]{ShipmentStatus.ARRIVED, 1L},
                        new Object[]{ShipmentStatus.PENDING_FIRST_CHECK, 3L}));

        ShipmentStats stats = shipmentService.getVisibleShipmentStats(null);

        assertThat(stats.total()).isEqualTo(11);
        assertThat(stats.inTransit()).isEqualTo(5);
        assertThat(stats.nearArrival()).isEqualTo(2);
        assertThat(stats.arrived()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe marcar el embarque para verificación manual sin alterar la fecha de última consulta")
    void shouldMarkShipmentForManualCheck() {
        LocalDateTime lastChecked = LocalDateTime.of(2026, 10, 20, 8, 0);
        Shipment shipment = Shipment.builder()
                .id(5L)
                .trackingNumber("HLCUNG12607EKZE0")
                .carrier(Carrier.HAPAG_LLOYD)
                .status(ShipmentStatus.IN_TRANSIT)
                .lastCheckedAt(lastChecked)
                .build();
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        shipmentService.markForManualCheck(shipment);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.MANUAL_CHECK);
        assertThat(shipment.getLastCheckedAt()).isEqualTo(lastChecked);
        verify(shipmentRepository).save(shipment);
    }

    @Test
    @DisplayName("No debe degradar a verificación manual un embarque ya arribado")
    void shouldNotDowngradeArrivedShipmentToManualCheck() {
        Shipment arrived = Shipment.builder()
                .id(6L)
                .trackingNumber("HLCUNG12608BMJY0")
                .carrier(Carrier.HAPAG_LLOYD)
                .status(ShipmentStatus.ARRIVED)
                .build();

        shipmentService.markForManualCheck(arrived);

        assertThat(arrived.getStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        verify(shipmentRepository, never()).save(any());
    }
}
