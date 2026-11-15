package com.shiparrival.service.dto;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;

import java.time.LocalDateTime;

/**
 * Criterios de filtrado del dashboard de embarques. Todos los campos son
 * opcionales: un valor {@code null} o en blanco significa "sin filtro".
 */
public record ShipmentFilter(
        String search,
        Carrier carrier,
        ShipmentStatus status,
        String port,
        LocalDateTime etaFrom,
        LocalDateTime etaTo
) {
    public static ShipmentFilter empty() {
        return new ShipmentFilter(null, null, null, null, null, null);
    }
}
