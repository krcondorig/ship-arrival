package com.shiparrival.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Avisa de que el cliente primario de Hapag-Lloyd (Chrome) falló y que se usó
 * el respaldo (ZenRows).
 *
 * <p>El aviso tiene dos destinos. Siempre queda en el log de la aplicación, y
 * además se emite como anotación {@code ::warning::} en stdout cuando corre en
 * GitHub Actions: así el fallo del camino principal aparece en la lista de
 * anotaciones del run en lugar de perderse entre las líneas del log. Fuera del
 * runner no se imprime nada, para no ensuciar la consola de Render.</p>
 */
@Slf4j
@Component
public class TrackingFallbackAlerter {

    static final String GITHUB_ANNOTATION_PREFIX = "::warning::";

    /**
     * Registra el respaldo en el log y, si corre en GitHub Actions, lo publica
     * como anotación del run.
     *
     * @param trackingNumber guía que se estaba consultando
     * @param chromeFailure  fallo del camino primario
     */
    public void warnFallback(String trackingNumber, Throwable chromeFailure) {
        String message = formatWarningMessage(trackingNumber, chromeFailure);
        log.warn(message);
        if (isGitHubActions()) {
            // Una sola línea: un salto de línea cortaría la anotación del run.
            System.out.println(GITHUB_ANNOTATION_PREFIX + message);
        }
    }

    /**
     * Mensaje de una línea con la guía y el motivo del fallo del primario.
     */
    static String formatWarningMessage(String trackingNumber, Throwable chromeFailure) {
        String guide = sanitize(trackingNumber == null || trackingNumber.isBlank()
                ? "(sin guía)"
                : trackingNumber);
        return sanitize("Hapag-Lloyd: Chrome falló para la guía " + guide + " ("
                + describeFailure(chromeFailure)
                + "). Se usó ZenRows como respaldo.");
    }

    static String describeFailure(Throwable failure) {
        if (failure == null) {
            return "sin detalle";
        }
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return detail;
    }

    /** Colapsa espacios y saltos de línea: la anotación debe caber en una línea. */
    static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    static boolean isGitHubActions() {
        return "true".equals(System.getenv("GITHUB_ACTIONS"));
    }
}
