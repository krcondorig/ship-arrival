package com.shiparrival.domain;

/**
 * Estado de una ejecución de monitoreo.
 *
 * <p>{@code STALE} marca ejecuciones que quedaron a medias porque el runner
 * murió (por ejemplo, el teléfono que sirve de salida se apagó).</p>
 */
public enum TrackingRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STALE
}
