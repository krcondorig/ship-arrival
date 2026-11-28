package com.shiparrival.config;

import com.shiparrival.adapter.hapaglloyd.HapagLloydContainer;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingException;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingSnapshot;
import com.shiparrival.adapter.hapaglloyd.ZenRowsHapagLloydTrackingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueba manual del cliente compuesto de Hapag-Lloyd contra ZenRows real.
 *
 * <p>El compuesto solo se invoca cuando hay una guía de Hapag activa en la base,
 * y hoy no hay ninguna, así que el respaldo nunca se había ejercitado. Esta
 * prueba lo hace sin PostgreSQL ni dashboard: construye el compuesto a mano con
 * un primario que falla a propósito y el respaldo real de ZenRows.</p>
 *
 * <p>Se omite en la suite normal. Para ejecutarla:</p>
 * <pre>
 * $env:HAPAG_LLOYD_FALLBACK_INTEGRATION="true"
 * $env:HAPAG_LLOYD_GUIDE="HLCU..."
 * $env:ZENROWS_API_KEY="..."
 * .\mvnw.cmd test -Dtest=HapagLloydFallbackIntegrationTest
 * </pre>
 *
 * <p>En CI la ejecuta el workflow manual
 * {@code .github/workflows/hapag-fallback-verification.yml}. Como la clase entera
 * exige {@code HAPAG_LLOYD_FALLBACK_INTEGRATION=true}, una vez solicitada la
 * integración una credencial faltante ya no se omite: el test <strong>falla</strong>.</p>
 */
@EnabledIfEnvironmentVariable(named = "HAPAG_LLOYD_FALLBACK_INTEGRATION", matches = "true")
class HapagLloydFallbackIntegrationTest {

    @Test
    @DisplayName("El respaldo de ZenRows resuelve la guía cuando el camino primario falla")
    void fallbackResolvesGuideWhenPrimaryFails() {
        String guide = System.getenv("HAPAG_LLOYD_GUIDE");
        assertThat(guide)
                .as("HAPAG_LLOYD_GUIDE debe estar definida para verificar el respaldo")
                .isNotBlank();

        String apiKey = System.getenv("ZENROWS_API_KEY");
        assertThat(apiKey)
                .as("ZENROWS_API_KEY debe estar definida; sin ella el respaldo no se verifica")
                .isNotBlank();

        HapagLloydTrackingClient failingPrimary = new HapagLloydTrackingClient() {
            @Override
            public HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber) {
                throw new HapagLloydTrackingException(
                        "SOCKS5 127.0.0.1:40000 sin respuesta (simulado)");
            }

            @Override
            public void close() {
                // Nada que liberar: el primario es un doble de prueba.
            }
        };

        ZenRowsHapagLloydTrackingClient zenRows =
                new ZenRowsHapagLloydTrackingClient(
                        RestClient.builder(), apiKey, "https://api.zenrows.com/v1/", 180);

        HapagLloydFallbackTrackingClient composite = new HapagLloydFallbackTrackingClient(
                failingPrimary, zenRows, new TrackingFallbackAlerter());

        try {
            HapagLloydTrackingSnapshot snapshot = composite.fetchTrackingSnapshot(guide);

            assertThat(snapshot)
                    .as("el respaldo de ZenRows debe devolver un snapshot para %s", guide)
                    .isNotNull();
            assertThat(snapshot.getContainers())
                    .as("el snapshot debe traer al menos un contenedor")
                    .isNotEmpty();
            assertThat(snapshot.getContainers().get(0).getContainerNumber())
                    .as("el primer contenedor debe traer su número")
                    .isNotBlank();
        } finally {
            composite.close();
        }
    }

    @Test
    @DisplayName("Si ambos caminos fallan, la excepción menciona los dos motivos")
    void bothPathsFailingMentionsBothReasons() {
        // Nunca llega a la red: la guía literal es solo para el mensaje.
        String guide = "TEST";

        HapagLloydTrackingClient failingPrimary = new HapagLloydTrackingClient() {
            @Override
            public HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber) {
                throw new HapagLloydTrackingException("Chrome: WARP no conecta (simulado)");
            }

            @Override
            public void close() {
                // Nada que liberar: el primario es un doble de prueba.
            }
        };

        HapagLloydTrackingClient failingFallback = new HapagLloydTrackingClient() {
            @Override
            public HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber) {
                throw new HapagLloydTrackingException("ZenRows: sin creditos (simulado)");
            }

            @Override
            public void close() {
                // Nada que liberar: el respaldo es un doble de prueba.
            }
        };

        HapagLloydFallbackTrackingClient composite = new HapagLloydFallbackTrackingClient(
                failingPrimary, failingFallback, new TrackingFallbackAlerter());

        try {
            assertThatThrownBy(() -> composite.fetchTrackingSnapshot(guide))
                    .as("ambos caminos fallan: debe propagarse la excepción de Hapag-Lloyd")
                    .isInstanceOf(HapagLloydTrackingException.class)
                    .hasMessageContaining("WARP no conecta")
                    .hasMessageContaining("sin creditos");
        } finally {
            composite.close();
        }
    }
}
