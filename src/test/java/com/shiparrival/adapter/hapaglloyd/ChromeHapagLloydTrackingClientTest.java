package com.shiparrival.adapter.hapaglloyd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la construccion del comando de Chrome del cliente de Hapag-Lloyd.
 *
 * <p>No lanzan Chrome: solo verifican la lista de argumentos, de modo que el
 * proxy SOCKS5 pueda configurarse sin abrir un navegador real.</p>
 */
class ChromeHapagLloydTrackingClientTest {

    private static final String CHROME_PATH = "/usr/bin/google-chrome";
    private static final String INITIAL_URL =
            "https://www.hapag-lloyd.com/en/online-business/track/"
                    + "track-by-booking-solution.html?blno=HLCUNG12607EKZE0";

    @Test
    @DisplayName("Debe agregar --proxy-server cuando se configura un proxy SOCKS5")
    void shouldAddProxyServerArgumentWhenProxyConfigured() {
        List<String> command = ChromeHapagLloydTrackingClient.buildChromeCommand(
                CHROME_PATH,
                Path.of("./data/hapag-chrome-profile"),
                9222,
                false,
                "socks5://127.0.0.1:40000",
                INITIAL_URL);

        assertThat(command)
                .contains("--proxy-server=socks5://127.0.0.1:40000")
                .contains(INITIAL_URL);

        int proxyIndex = command.indexOf("--proxy-server=socks5://127.0.0.1:40000");
        int urlIndex = command.indexOf(INITIAL_URL);
        assertThat(proxyIndex)
                .as("el flag --proxy-server debe ir antes de la URL inicial")
                .isLessThan(urlIndex);
    }

    @Test
    @DisplayName("No debe agregar --proxy-server cuando el proxy es nulo")
    void shouldNotAddProxyServerArgumentWhenProxyIsNull() {
        List<String> command = ChromeHapagLloydTrackingClient.buildChromeCommand(
                CHROME_PATH,
                Path.of("./data/hapag-chrome-profile"),
                9222,
                false,
                null,
                INITIAL_URL);

        assertThat(argumentosConPrefijo(command, "--proxy-server")).isEmpty();
    }

    @Test
    @DisplayName("No debe agregar --proxy-server cuando el proxy está en blanco")
    void shouldNotAddProxyServerArgumentWhenProxyIsBlank() {
        List<String> command = ChromeHapagLloydTrackingClient.buildChromeCommand(
                CHROME_PATH,
                Path.of("./data/hapag-chrome-profile"),
                9222,
                false,
                "   ",
                INITIAL_URL);

        assertThat(argumentosConPrefijo(command, "--proxy-server")).isEmpty();
    }

    @Test
    @DisplayName("La URL del detalle rellena el contenedor compacto como ++")
    void containerDetailUrlPadsCompactContainer() {
        assertThat(ChromeHapagLloydTrackingClient.buildContainerDetailUrl("HLBU2959982"))
                .endsWith("?view=S8510&container=HLBU++2959982");
    }

    @Test
    @DisplayName("La URL del detalle conserva el relleno que ya trae el listado")
    void containerDetailUrlKeepsPaddedContainer() {
        assertThat(ChromeHapagLloydTrackingClient.buildContainerDetailUrl("HLBU  2959982"))
                .endsWith("?view=S8510&container=HLBU++2959982");
    }

    @Test
    @DisplayName("La URL del detalle no escapa el + a %2B")
    void containerDetailUrlDoesNotEscapePlus() {
        assertThat(ChromeHapagLloydTrackingClient.buildContainerDetailUrl("HLBU2959982"))
                .doesNotContain("%2B");
    }

    @Test
    @DisplayName("Un detalle real se reconoce por al menos dos estados")
    void realDetailNeedsTwoStates() {
        assertThat(ChromeHapagLloydTrackingClient.isRealDetailText(
                "Discharged\tMANZANILLO, COL (MX)\t2026-10-22\nLoaded\tSHANGHAI\t2026-09-30"))
                .isTrue();
    }

    @Test
    @DisplayName("La shell de la SPA no se confunde con un detalle")
    void spaShellIsNotRealDetail() {
        assertThat(ChromeHapagLloydTrackingClient.isRealDetailText(
                "Track your shipment. Please enter a container number."))
                .isFalse();
    }

    @Test
    @DisplayName("Un solo estado no basta para considerar detalle real")
    void singleStateIsNotEnough() {
        assertThat(ChromeHapagLloydTrackingClient.isRealDetailText("Discharged"))
                .isFalse();
    }

    private static List<String> argumentosConPrefijo(List<String> command, String prefix) {
        return command.stream().filter(arg -> arg.startsWith(prefix)).toList();
    }
}
