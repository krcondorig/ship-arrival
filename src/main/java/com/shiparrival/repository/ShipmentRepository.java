package com.shiparrival.repository;

import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    List<Shipment> findByActiveTrue();

    /**
     * Embarques visibles en el dashboard: los que están activos (en tránsito,
     * pendientes o con error) y los que arribaron hace 3 días o menos.
     * Los arribados con fecha anterior a {@code cutoff} quedan archivados.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE ((s.active = true AND s.status <> :arrivedStatus)
                   OR (s.status = :arrivedStatus
                       AND COALESCE(s.actualArrival, s.lastCheckedAt, s.updatedAt) >= :cutoff))
              AND (LOWER(s.trackingNumber) LIKE :search
                   OR LOWER(s.containerNumber) LIKE :search
                   OR LOWER(s.vesselName) LIKE :search)
            """)
    Page<Shipment> findVisibleShipments(@Param("cutoff") LocalDateTime cutoff,
                                        @Param("arrivedStatus") ShipmentStatus arrivedStatus,
                                        @Param("search") String search,
                                        Pageable pageable);

    /**
     * Conteo por estado de los embarques visibles, usado por las tarjetas
     * de métricas del dashboard.
     */
    @Query("""
            SELECT s.status, COUNT(s) FROM Shipment s
            WHERE ((s.active = true AND s.status <> :arrivedStatus)
                   OR (s.status = :arrivedStatus
                       AND COALESCE(s.actualArrival, s.lastCheckedAt, s.updatedAt) >= :cutoff))
              AND (LOWER(s.trackingNumber) LIKE :search
                   OR LOWER(s.containerNumber) LIKE :search
                   OR LOWER(s.vesselName) LIKE :search)
            GROUP BY s.status
            """)
    List<Object[]> countVisibleByStatus(@Param("cutoff") LocalDateTime cutoff,
                                        @Param("arrivedStatus") ShipmentStatus arrivedStatus,
                                        @Param("search") String search);

    /**
     * Embarques que ya cumplieron su ciclo de vida y pueden borrarse.
     *
     * <p>Cada estado envejece por su propio reloj:</p>
     * <ul>
     *   <li>{@code ARRIVED}: desde el arribo real (o el último chequeo si la
     *       naviera no dio fecha).</li>
     *   <li>{@code CANCELLED}: desde la última modificación; una guía cancelada
     *       ya no se vuelve a tocar, así que la fecha deja de moverse.</li>
     *   <li>{@code ERROR_CHECKING}: desde la ETA. El estado solo dice "la última
     *       consulta falló", y cada intento fallido refresca {@code lastCheckedAt};
     *       por eso se usa la ETA, que sí es fija. Sin ETA no se borra: no hay
     *       forma de saber si la guía todavía puede llegar.</li>
     * </ul>
     */
    @Query("""
            SELECT s.id FROM Shipment s
            WHERE (s.status = :arrivedStatus
                   AND COALESCE(s.actualArrival, s.lastCheckedAt, s.updatedAt) < :cutoff)
               OR (s.status = :cancelledStatus
                   AND s.updatedAt < :cutoff)
               OR (s.status = :errorStatus
                   AND s.estimatedArrival IS NOT NULL
                   AND s.estimatedArrival < :cutoff)
            """)
    List<Long> findExpiredIds(@Param("cutoff") LocalDateTime cutoff,
                              @Param("arrivedStatus") ShipmentStatus arrivedStatus,
                              @Param("cancelledStatus") ShipmentStatus cancelledStatus,
                              @Param("errorStatus") ShipmentStatus errorStatus);
}
