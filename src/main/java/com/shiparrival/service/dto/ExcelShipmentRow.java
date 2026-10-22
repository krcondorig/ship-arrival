package com.shiparrival.service.dto;

import com.shiparrival.domain.Carrier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelShipmentRow {

    private String trackingNumber;

    /**
     * Naviera leída de la columna NAVIERA. Si es null, se usa la naviera
     * predeterminada seleccionada en el formulario de carga.
     */
    private Carrier carrier;
}
