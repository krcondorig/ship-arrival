package com.shiparrival.config;

import com.shiparrival.adapter.hapaglloyd.ChromeHapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.ZenRowsHapagLloydTrackingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifica qué cliente expone la configuración en cada combinación de
 * propiedades.
 *
 * <p>No usa {@code ApplicationContextRunner} (el proyecto no lo emplea) ni
 * levanta un contexto completo: se instancia la configuración directamente y se
 * le pasan {@code ObjectProvider} reales construidos sobre un
 * {@code StaticListableBeanFactory}, que reproduce tanto el caso "el bean
 * existe" como "el bean no existe".</p>
 */
class HapagLloydTrackingClientConfigTest {

    private final HapagLloydTrackingClientConfig config = new HapagLloydTrackingClientConfig();
    private final TrackingFallbackAlerter alerter = mock(TrackingFallbackAlerter.class);

    private HapagLloydTrackingClient build(boolean chromePresent,
                                          boolean zenRowsPresent,
                                          String client,
                                          boolean fallbackEnabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (chromePresent) {
            beans.addBean("chromeHapagLloydTrackingClient",
                    mock(ChromeHapagLloydTrackingClient.class));
        }
        if (zenRowsPresent) {
            beans.addBean("zenRowsHapagLloydTrackingClient",
                    mock(ZenRowsHapagLloydTrackingClient.class));
        }
        beans.addBean("restClientBuilder", RestClient.builder());

        return config.hapagLloydTrackingClient(
                beans.getBeanProvider(ChromeHapagLloydTrackingClient.class),
                beans.getBeanProvider(ZenRowsHapagLloydTrackingClient.class),
                beans.getBeanProvider(RestClient.Builder.class),
                alerter,
                client,
                fallbackEnabled,
                "test-key",
                "https://api.zenrows.com/v1/",
                180);
    }

    @Test
    @DisplayName("client=zenrows devuelve solo ZenRows, sin compuesto (Render)")
    void zenRowsOnlyWhenClientIsZenRows() {
        HapagLloydTrackingClient result = build(false, true, "zenrows", false);

        assertThat(result).isInstanceOf(ZenRowsHapagLloydTrackingClient.class);
        assertThat(result).isNotInstanceOf(HapagLloydFallbackTrackingClient.class);
    }

    @Test
    @DisplayName("client=chrome con fallback=false devuelve solo Chrome (runner anterior)")
    void chromeOnlyWhenFallbackDisabled() {
        HapagLloydTrackingClient result = build(true, false, "chrome", false);

        assertThat(result).isInstanceOf(ChromeHapagLloydTrackingClient.class);
        assertThat(result).isNotInstanceOf(HapagLloydFallbackTrackingClient.class);
    }

    @Test
    @DisplayName("client=chrome con fallback=true devuelve el compuesto")
    void compositeWhenChromeWithFallback() {
        HapagLloydTrackingClient result = build(true, false, "chrome", true);

        assertThat(result).isInstanceOf(HapagLloydFallbackTrackingClient.class);
    }

    @Test
    @DisplayName("El compuesto funciona aunque el bean de ZenRows no exista en el contexto")
    void compositeBuildsZenRowsLocallyWhenBeanIsAbsent() {
        HapagLloydTrackingClient result = build(true, false, "chrome", true);

        assertThat(result).isInstanceOf(HapagLloydFallbackTrackingClient.class);
        // Se cierra sin lanzar: el ZenRows construido con new no tiene recursos.
        result.close();
    }

    @Test
    @DisplayName("client=chrome con fallback=true pero sin bean de Chrome cae a ZenRows sin romper")
    void degradesToZenRowsWhenChromeBeanIsMissing() {
        HapagLloydTrackingClient result = build(false, true, "chrome", true);

        assertThat(result).isInstanceOf(ZenRowsHapagLloydTrackingClient.class);
        assertThat(result).isNotInstanceOf(HapagLloydFallbackTrackingClient.class);
    }

    @Test
    @DisplayName("Sin propiedad de cliente, el default es zenrows")
    void defaultsToZenRowsWhenPropertyIsNull() {
        HapagLloydTrackingClient result = build(false, true, null, false);

        assertThat(result).isInstanceOf(ZenRowsHapagLloydTrackingClient.class);
    }

    @Test
    @DisplayName("La propiedad del respaldo está declarada con default false")
    void fallbackPropertyDefaultsToFalse() throws Exception {
        String properties = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.properties"));

        assertThat(properties).contains(
                "hapag-lloyd.fallback.enabled=${HAPAG_LLOYD_FALLBACK_ENABLED:false}");
    }
}
