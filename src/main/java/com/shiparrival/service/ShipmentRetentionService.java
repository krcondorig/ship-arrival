package com.shiparrival.service;

import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Borra los embarques que ya cumplieron su ciclo de vida para que la base no
 * crezca sin límite.
 *
 * <p>Se ejecuta al final de cada ciclo de monitoreo, de modo que también corre
 * cuando el ciclo lo dispara el programador diario, el botón "Monitorear Ahora"
 * o la ejecución desatendida en GitHub Actions.</p>
 *
 * <p>El borrado es definitivo: se lleva el embarque y su historial. El plazo se
 * controla con {@code tracking.retention.days}; un valor de 0 o menor desactiva
 * la limpieza.</p>
 */
@Slf4j
@Service
public class ShipmentRetentionService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentService shipmentService;
    private final int retentionDays;

    public ShipmentRetentionService(ShipmentRepository shipmentRepository,
                                    ShipmentService shipmentService,
                                    @Value("${tracking.retention.days:7}") int retentionDays) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentService = shipmentService;
        this.retentionDays = retentionDays;
    }

    /**
     * Elimina los embarques vencidos y devuelve cuántos borró.
     *
     * @param now instante de referencia; se recibe por parámetro para que las
     *            pruebas no dependan del reloj.
     */
    @Transactional
    public int purgeExpired(LocalDateTime now) {
        if (retentionDays <= 0) {
            return 0;
        }

        LocalDateTime cutoff = now.minusDays(retentionDays);
        List<Long> expired = shipmentRepository.findExpiredIds(
                cutoff,
                ShipmentStatus.ARRIVED,
                ShipmentStatus.CANCELLED,
                ShipmentStatus.ERROR_CHECKING);

        for (Long id : expired) {
            // deleteShipment borra primero el historial: hay una clave foránea.
            shipmentService.deleteShipment(id);
        }

        if (!expired.isEmpty()) {
            log.info("Retención: {} embarque(s) eliminados con más de {} días.",
                    expired.size(), retentionDays);
        }
        return expired.size();
    }
}
