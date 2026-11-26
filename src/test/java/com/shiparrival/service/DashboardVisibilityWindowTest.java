package com.shiparrival.service;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.ShipmentFilter;
import com.shiparrival.service.dto.ShipmentStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fija el borde de la ventana de visibilidad del dashboard (7 días) con precisión
 * de horas y comprueba que la tabla y las tarjetas KPI la respetan por igual.
 *
 * <p>El fallo que motiva esta prueba es la divergencia: cuando cada método
 * calculaba su propio corte, la tabla podía mostrar una cifra y las tarjetas
 * otra. Por eso se comparan los dos resultados contra el mismo conjunto.</p>
 *
 * <p>Las siembras se colocan a 23 horas de un lado del borde y a 1 hora del otro
 * ({@code ARRIVED-6D23H} dentro, {@code ARRIVED-7D1H} fuera) para que la ventana
 * quede fijada en 7 y no en cualquier valor mayor: con una ventana de 8 días
 * {@code ARRIVED-7D1H} pasaría a ser visible y esta prueba fallaría. No se siembra
 * exactamente a 7 días porque ahí el orden entre la lectura de {@code now} de la
 * prueba y la del servicio decidiría el resultado; el margen de una hora por lado
 * absorbe esa deriva.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:visibilitywindow;DB_CLOSE_DELAY=-1",
        "app.auth.enabled=false"
})
class DashboardVisibilityWindowTest {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private ShipmentEventLogRepository eventLogRepository;

    @BeforeEach
    void cleanDatabase() {
        eventLogRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    private void saveArrived(String trackingNumber, LocalDateTime actualArrival) {
        shipmentRepository.save(Shipment.builder()
                .trackingNumber(trackingNumber)
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.ARRIVED)
                .active(false)
                .actualArrival(actualArrival)
                .build());
    }

    @Test
    @DisplayName("La tabla y las tarjetas KPI respetan el borde de 7 días con precisión de horas: 6d23h visible, 7d1h oculto")
    void shouldShareTheSameVisibilityWindowBetweenTableAndStats() {
        LocalDateTime now = LocalDateTime.now();

        // Dentro de la ventana de 7 días: siguen visibles aunque ya arribaron.
        saveArrived("ARRIVED-2D", now.minusDays(2));
        // Borde interior: 23 horas antes del corte de 7 días. Visible con ventana 7
        // (y también con 8), así que por sí sola no distingue.
        saveArrived("ARRIVED-6D23H", now.minusDays(6).minusHours(23));
        // Borde exterior: 1 hora más allá del corte de 7 días. Oculta con ventana 7
        // y visible con ventana 8: esta fila es la que fija la ventana en 7.
        saveArrived("ARRIVED-7D1H", now.minusDays(7).minusHours(1));
        // Muy fuera de la ventana: archivado, no se pinta en el dashboard.
        saveArrived("ARRIVED-9D", now.minusDays(9));

        shipmentRepository.save(Shipment.builder()
                .trackingNumber("IN-TRANSIT")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .build());

        List<String> table = shipmentService
                .getVisibleShipments(ShipmentFilter.empty(), PageRequest.of(0, 20))
                .getContent()
                .stream()
                .map(Shipment::getTrackingNumber)
                .toList();

        assertThat(table)
                .containsExactlyInAnyOrder("ARRIVED-2D", "ARRIVED-6D23H", "IN-TRANSIT")
                .doesNotContain("ARRIVED-7D1H", "ARRIVED-9D");

        ShipmentStats stats = shipmentService.getVisibleShipmentStats(ShipmentFilter.empty());

        // Mismo universo en los dos caminos: si divergen, el dashboard miente.
        assertThat(stats.total()).isEqualTo(table.size());
        assertThat(stats.arrived()).isEqualTo(2);
        assertThat(stats.inTransit()).isEqualTo(1);
        assertThat(stats.nearArrival()).isZero();
    }
}
