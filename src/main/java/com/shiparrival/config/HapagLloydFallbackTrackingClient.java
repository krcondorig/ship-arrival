package com.shiparrival.config;

import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingException;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Cliente compuesto de Hapag-Lloyd: consulta primero el camino primario
 * (Chrome sobre WARP en el runner) y, si falla, reintenta con el respaldo
 * (ZenRows).
 *
 * <p>El camino primario es el barato y el que se quiere conservar, pero cuando
 * el exit node de WARP no está anunciándose el ciclo completo se queda sin
 * datos. El respaldo permite que el run termine con la guía actualizada a
 * cambio de una consulta de pago, y deja un aviso visible para que el operador
 * sepa que Chrome no funcionó.</p>
 *
 * <p>Si ambos caminos fallan, la excepción propagada menciona <strong>los dos
 * motivos</strong>: el del primario es el diagnóstico que importa, y perderlo
 * al envolver el error del respaldo dejaría el fallo sin explicación.</p>
 */
@Slf4j
public class HapagLloydFallbackTrackingClient implements HapagLloydTrackingClient {

    private final HapagLloydTrackingClient primaryClient;
    private final HapagLloydTrackingClient fallbackClient;
    private final TrackingFallbackAlerter alerter;

    public HapagLloydFallbackTrackingClient(HapagLloydTrackingClient primaryClient,
                                           HapagLloydTrackingClient fallbackClient,
                                           TrackingFallbackAlerter alerter) {
        this.primaryClient = Objects.requireNonNull(primaryClient, "primaryClient");
        this.fallbackClient = Objects.requireNonNull(fallbackClient, "fallbackClient");
        this.alerter = Objects.requireNonNull(alerter, "alerter");
    }

    @Override
    public HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber) {
        HapagLloydTrackingSnapshot snapshot;
        try {
            snapshot = primaryClient.fetchTrackingSnapshot(trackingNumber);
        } catch (RuntimeException primaryFailure) {
            alerter.warnFallback(trackingNumber, primaryFailure);
            return fetchFromFallback(trackingNumber, primaryFailure);
        }

        if (snapshot != null) {
            return snapshot;
        }

        // Un primario que devuelve vacío sin lanzar es, a efectos prácticos, un
        // fallo: sin snapshot el adaptador no tiene nada que mapear.
        HapagLloydTrackingException emptyResult = new HapagLloydTrackingException(
                "El cliente primario de Hapag-Lloyd devolvió un snapshot vacío");
        alerter.warnFallback(trackingNumber, emptyResult);
        return fetchFromFallback(trackingNumber, emptyResult);
    }

    private HapagLloydTrackingSnapshot fetchFromFallback(String trackingNumber,
                                                        RuntimeException primaryFailure) {
        try {
            HapagLloydTrackingSnapshot snapshot = fallbackClient.fetchTrackingSnapshot(trackingNumber);
            if (snapshot == null) {
                throw new HapagLloydTrackingException(
                        "El cliente de respaldo de Hapag-Lloyd devolvió un snapshot vacío");
            }
            log.info("Hapag-Lloyd para la guía {} se resolvió con el respaldo de ZenRows",
                    trackingNumber);
            return snapshot;
        } catch (RuntimeException fallbackFailure) {
            throw new HapagLloydTrackingException(
                    "Hapag-Lloyd falló en los dos caminos para la guía " + trackingNumber
                            + ". Chrome: " + describe(primaryFailure)
                            + " | ZenRows: " + describe(fallbackFailure),
                    primaryFailure);
        }
    }

    private String describe(Throwable failure) {
        if (failure == null) {
            return "sin detalle";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Cierra los dos clientes. Cada cierre va en su propio {@code try}: un fallo
     * al liberar el respaldo no puede impedir que se libere Chrome (y al revés),
     * y el ciclo nunca debe romperse por un cierre.
     */
    @Override
    public void close() {
        closeQuietly(primaryClient, "primario");
        closeQuietly(fallbackClient, "respaldo");
    }

    private void closeQuietly(HapagLloydTrackingClient client, String role) {
        try {
            client.close();
        } catch (Exception ex) {
            log.warn("No se pudo cerrar el cliente {} de Hapag-Lloyd: {}", role, ex.getMessage());
        }
    }
}
