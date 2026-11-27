package com.shiparrival.domain;

/**
 * Estado de una ejecución de monitoreo.
 *
 * <p>{@code STALE} marca ejecuciones que quedaron a medias porque el runner
 * murió (por ejemplo, el proxy SOCKS5 de Cloudflare WARP dejó de responder).</p>
 */
public enum TrackingRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STALE
}
