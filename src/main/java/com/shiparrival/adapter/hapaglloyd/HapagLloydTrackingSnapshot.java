package com.shiparrival.adapter.hapaglloyd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Datos extraídos de la página pública de Track & Trace de Hapag-Lloyd.
 *
 * <p>Se mantiene como un snapshot independiente del navegador para poder
 * probar el mapeo a {@code TrackingResult} sin abrir Chrome.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HapagLloydTrackingSnapshot {

    private String trackingNumber;
    private List<HapagLloydContainer> containers;
}
