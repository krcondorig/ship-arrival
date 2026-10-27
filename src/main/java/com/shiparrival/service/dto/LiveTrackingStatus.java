package com.shiparrival.service.dto;

import java.util.List;

/**
 * Estado que ve el dashboard mientras un monitoreo corre en la nube.
 *
 * @param phase                 {@code QUEUED}, {@code PREPARING}, {@code RUNNING},
 *                              {@code COMPLETED}, {@code FAILED} o {@code UNKNOWN}
 * @param running               true mientras el usuario deba seguir esperando
 * @param total                 guías a monitorear; 0 mientras no se sabe
 * @param processed             guías ya consultadas
 * @param updated               guías actualizadas con datos de la naviera
 * @param failed                guías que fallaron
 * @param alertsSent            alertas enviadas en esta ejecución
 * @param currentTrackingNumber guía en curso
 * @param message               explicación legible del estado
 * @param runUrl                enlace a la ejecución en GitHub, si existe
 * @param details               detalle por guía
 */
public record LiveTrackingStatus(String phase,
                                 boolean running,
                                 int total,
                                 int processed,
                                 int updated,
                                 int failed,
                                 int alertsSent,
                                 String currentTrackingNumber,
                                 String message,
                                 String runUrl,
                                 List<String> details) {
}
