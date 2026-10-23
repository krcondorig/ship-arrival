package com.shiparrival.adapter.hapaglloyd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Contenedor tal como aparece en la tabla de resultados de Hapag-Lloyd.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HapagLloydContainer {

    private String containerNumber;
    private String containerType;
    private String status;
    private String date;
    private String place;
    private List<HapagLloydMovement> movements;
}
