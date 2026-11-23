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
    MANUAL_CHECK;

    /**
     * Texto que ve el usuario final para este estado.
     *
     * <p>Se usa {@code getLabel()} para que Thymeleaf lo resuelva como propiedad
     * ({@code ${st.label}}). El {@code switch} es exhaustivo: agregar una
     * constante nueva rompe la compilación hasta que se le asigne su etiqueta,
     * de modo que nunca se escapa un nombre crudo de enum a la interfaz.</p>
     *
     * <p>Los textos coinciden con los badges de la columna Estado del dashboard
     * para no crear dos vocabularios distintos.</p>
     */
    public String getLabel() {
        return switch (this) {
            case PENDING_FIRST_CHECK -> "Pendiente";
            case IN_TRANSIT -> "En Tránsito";
            case NEAR_ARRIVAL -> "Arribo Próximo";
            case ARRIVED -> "Arribado";
            case CANCELLED -> "Cancelado";
            case ERROR_CHECKING -> "Error";
            case MANUAL_CHECK -> "Verificar manual";
        };
    }

    /**
     * Criterio de borrado manual desde el dashboard.
     *
     * <p>Solo se permite eliminar una guía que todavía no ha sido monitoreada
     * ({@code PENDING_FIRST_CHECK}) o cuya consulta a la naviera falló
     * ({@code ERROR_CHECKING}). En cualquier otro estado el embarque tiene
     * información útil que no debe perderse con un clic accidental.</p>
     *
     * <p>Este criterio es solo para la acción manual del operador: la purga
     * automática de retención ({@code ShipmentRetentionService}) borra también
     * guías arribadas y canceladas, y por eso no consulta este método.</p>
     */
    public boolean canBeDeletedManually() {
        return this == PENDING_FIRST_CHECK || this == ERROR_CHECKING;
    }
}
