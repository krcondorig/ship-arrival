package com.shiparrival.domain;

public enum Carrier {
    ONE,
    MSC,
    MAERSK,
    HAPAG_LLOYD,
    UNKNOWN;

    /**
     * Texto que ve el usuario final para esta naviera.
     *
     * <p>Se usa {@code getLabel()} para que Thymeleaf lo resuelva como propiedad
     * ({@code ${s.carrier.label}}), igual que {@link ShipmentStatus#getLabel()}.
     * El {@code switch} es exhaustivo: agregar una naviera nueva rompe la
     * compilación hasta que se le asigne su etiqueta, de modo que nunca se
     * escapa un nombre crudo de enum a la interfaz.</p>
     *
     * <p>El texto es el nombre comercial de la naviera, sin guiones bajos: la
     * versión en minúsculas de la etiqueta ({@code hapag-lloyd}) es también la
     * clase CSS que colorea el badge en el historial.</p>
     */
    public String getLabel() {
        return switch (this) {
            case ONE -> "ONE";
            case MSC -> "MSC";
            case MAERSK -> "MAERSK";
            case HAPAG_LLOYD -> "Hapag-Lloyd";
            case UNKNOWN -> "Desconocida";
        };
    }
}
