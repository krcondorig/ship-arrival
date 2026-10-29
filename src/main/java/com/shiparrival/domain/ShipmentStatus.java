package com.shiparrival.domain;

public enum ShipmentStatus {
    PENDING_FIRST_CHECK,
    IN_TRANSIT,
    NEAR_ARRIVAL,
    ARRIVED,
    CANCELLED,
    ERROR_CHECKING,
    /**
     * La naviera no se puede rastrear automáticamente (por ejemplo, Hapag-Lloyd
     * por el bloqueo de su portal). Un operador debe consultar el ETA a mano.
     */
    MANUAL_CHECK
}
