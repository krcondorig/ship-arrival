package com.shiparrival.service.dto;

/**
 * Estadísticas de los embarques visibles en el dashboard.
 */
public record ShipmentStats(long total, long inTransit, long nearArrival, long arrived) {
}
