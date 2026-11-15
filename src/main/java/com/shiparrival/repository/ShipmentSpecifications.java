package com.shiparrival.repository;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

/**
 * Specifications dinámicas para el filtrado del dashboard de embarques.
 *
 * <p>Se construyen solo los predicados cuyos criterios no son nulos, evitando
 * así parámetros nulos en la consulta SQL (que PostgreSQL no puede tipar).</p>
 */
public final class ShipmentSpecifications {

    private ShipmentSpecifications() {
    }

    public static Specification<Shipment> visible(LocalDateTime cutoff, ShipmentStatus arrivedStatus) {
        return (root, query, cb) -> cb.or(
                cb.and(cb.isTrue(root.get("active")),
                        cb.notEqual(root.get("status"), arrivedStatus)),
                cb.and(cb.equal(root.get("status"), arrivedStatus),
                        cb.greaterThanOrEqualTo(
                                cb.coalesce(cb.coalesce(root.get("actualArrival"), root.get("lastCheckedAt")),
                                        root.get("updatedAt")),
                                cutoff))
        );
    }

    public static Specification<Shipment> search(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return null;
        }
        String pattern = "%" + searchTerm.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("trackingNumber")), pattern),
                cb.like(cb.lower(root.get("containerNumber")), pattern),
                cb.like(cb.lower(root.get("vesselName")), pattern)
        );
    }

    public static Specification<Shipment> carrier(Carrier carrier) {
        if (carrier == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("carrier"), carrier);
    }

    public static Specification<Shipment> status(ShipmentStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Shipment> port(String port) {
        if (port == null || port.isBlank()) {
            return null;
        }
        String pattern = "%" + port.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("portOfDischarge")), pattern);
    }

    public static Specification<Shipment> etaFrom(LocalDateTime etaFrom) {
        if (etaFrom == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("estimatedArrival"), etaFrom);
    }

    public static Specification<Shipment> etaTo(LocalDateTime etaTo) {
        if (etaTo == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("estimatedArrival"), etaTo);
    }
}
