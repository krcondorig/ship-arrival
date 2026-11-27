package com.shiparrival.config;

import com.shiparrival.adapter.hapaglloyd.ChromeHapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.ZenRowsHapagLloydTrackingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.util.Locale;

/**
 * Elige el cliente de Hapag-Lloyd que consume {@code HapagLloydTrackingAdapter}.
 *
 * <p>El adaptador inyecta {@link HapagLloydTrackingClient} por tipo, así que en
 * cuanto conviven dos clientes en el contexto hace falta un bean
 * {@link Primary} que resuelva la ambigüedad. Este es ese bean, y su contenido
 * depende de dos propiedades:</p>
 *
 * <ul>
 *   <li>{@code hapag-lloyd.client=zenrows} (Render, por defecto): solo ZenRows,
 *       exactamente el comportamiento anterior.</li>
 *   <li>{@code hapag-lloyd.client=chrome} + {@code hapag-lloyd.fallback.enabled=false}:
 *       solo Chrome, el comportamiento del runner antes de este cambio.</li>
 *   <li>{@code hapag-lloyd.client=chrome} + {@code hapag-lloyd.fallback.enabled=true}:
 *       el compuesto Chrome-con-respaldo-ZenRows.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class HapagLloydTrackingClientConfig {

    private static final String DEFAULT_ZENROWS_BASE_URL = "https://api.zenrows.com/v1/";

    /**
     * Cliente que verá el adaptador.
     *
     * <p>Chrome y ZenRows se piden con {@link ObjectProvider} porque su
     * existencia depende de condiciones de arranque distintas. ZenRows sigue
     * siendo condicional a {@code hapag-lloyd.client=zenrows}; con
     * {@code chrome} su bean no existe, así que se construye uno local cuando
     * hace falta como respaldo. Se hace con {@code new} y no con el contenedor
     * para que la configuración funcione igual si un encargo posterior quita esa
     * anotación condicional: con bean presente se reutiliza, sin él se crea.</p>
     */
    @Bean
    @Primary
    public HapagLloydTrackingClient hapagLloydTrackingClient(
            ObjectProvider<ChromeHapagLloydTrackingClient> chromeProvider,
            ObjectProvider<ZenRowsHapagLloydTrackingClient> zenRowsProvider,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            TrackingFallbackAlerter alerter,
            @Value("${hapag-lloyd.client:zenrows}") String configuredClient,
            @Value("${hapag-lloyd.fallback.enabled:false}") boolean fallbackEnabled,
            @Value("${hapag-lloyd.zenrows.api-key:}") String zenRowsApiKey,
            @Value("${hapag-lloyd.zenrows.base-url:" + DEFAULT_ZENROWS_BASE_URL + "}") String zenRowsBaseUrl,
            @Value("${hapag-lloyd.zenrows.timeout-seconds:180}") int zenRowsTimeoutSeconds) {

        String client = configuredClient == null
                ? "zenrows"
                : configuredClient.trim().toLowerCase(Locale.ROOT);
        ZenRowsHapagLloydTrackingClient zenRows = resolveZenRows(
                zenRowsProvider, restClientBuilderProvider,
                zenRowsApiKey, zenRowsBaseUrl, zenRowsTimeoutSeconds);

        if (!"chrome".equals(client)) {
            log.debug("Hapag-Lloyd usará solo ZenRows (hapag-lloyd.client={})", client);
            return zenRows;
        }

        ChromeHapagLloydTrackingClient chrome = chromeProvider.getIfAvailable();
        if (chrome == null) {
            // No debería ocurrir: la anotación condicional de Chrome exige
            // hapag-lloyd.client=chrome. Si pasa, el arranque no se rompe y el
            // ciclo sigue funcionando con el respaldo.
            log.warn("hapag-lloyd.client=chrome pero el cliente Chrome no está en el contexto; "
                    + "se usará solo ZenRows");
            return zenRows;
        }

        if (!fallbackEnabled) {
            log.debug("Hapag-Lloyd usará solo Chrome, sin respaldo");
            return chrome;
        }

        log.info("Hapag-Lloyd usará Chrome con respaldo de ZenRows");
        return new HapagLloydFallbackTrackingClient(chrome, zenRows, alerter);
    }

    private ZenRowsHapagLloydTrackingClient resolveZenRows(
            ObjectProvider<ZenRowsHapagLloydTrackingClient> zenRowsProvider,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            String apiKey,
            String baseUrl,
            int timeoutSeconds) {

        ZenRowsHapagLloydTrackingClient existing = zenRowsProvider.getIfAvailable();
        if (existing != null) {
            return existing;
        }

        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        // El constructor no valida la API key; la valida al consultar, así que
        // construirlo sin clave es seguro y el fallo se ve como error de rastreo.
        return new ZenRowsHapagLloydTrackingClient(builder, apiKey, baseUrl, timeoutSeconds);
    }
}
