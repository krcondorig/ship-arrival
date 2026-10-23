package com.shiparrival.adapter.hapaglloyd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Un movimiento de la tabla de detalles de un contenedor.
 *
 * <p>Los movimientos con {@code actual = true} son datos confirmados; los
 * demás son hitos planificados (la página los muestra sin negrita).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HapagLloydMovement {

    private String status;
    private String place;
    private String date;
    private String time;
    private String transport;
    private String voyageNumber;
    private boolean actual;
}
