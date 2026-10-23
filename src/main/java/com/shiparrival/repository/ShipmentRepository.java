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
}
