package com.shiparrival.adapter.hapaglloyd;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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

    @Test
    @DisplayName("Con headless el comando de Chrome lleva --headless=new")
    void headlessCommandContainsHeadlessFlag() {
        List<String> command = ChromeHapagLloydTrackingClient.buildChromeCommand(
                "/usr/bin/google-chrome", Path.of("/tmp/profile"), 9222, true, null,
                "https://example.com");
        assertThat(command).contains("--headless=new");
    }

    @Test
    @DisplayName("Sin headless el comando de Chrome no lleva --headless=new")
    void headfulCommandHasNoHeadlessFlag() {
        List<String> command = ChromeHapagLloydTrackingClient.buildChromeCommand(
                "/usr/bin/google-chrome", Path.of("/tmp/profile"), 9222, false, null,
                "https://example.com");
        assertThat(command).doesNotContain("--headless=new");
    }

    @Test
    @DisplayName("La variable de entorno HAPAG_LLOYD_HEADLESS gana sobre el valor de Spring")
    void envHeadlessWinsOverSpringValue() {
        assertThat(ChromeHapagLloydTrackingClient.resolveHeadless("true", false)).isTrue();
    }

    @Test
    @DisplayName("Sin variable de entorno se usa el valor de Spring")
    void springValueUsedWhenEnvIsAbsent() {
        assertThat(ChromeHapagLloydTrackingClient.resolveHeadless(null, true)).isTrue();
        assertThat(ChromeHapagLloydTrackingClient.resolveHeadless("", false)).isFalse();
    }

    @Test
    @DisplayName("El entorno puede desactivar headless aunque Spring lo active")
    void envCanDisableHeadless() {
        assertThat(ChromeHapagLloydTrackingClient.resolveHeadless("false", true)).isFalse();
    }

    private static final String DETAIL_HTML = """
            <table><tbody>
              <tr><th>Status</th><th>Location</th><th>Date</th></tr>
              <tr>
                <td class="strong">Vessel arrived</td>
                <td class="strong">MANZANILLO, COL (MX)</td>
                <td class="strong">2026-10-21</td>
                <td class="strong">08:00</td>
                <td class="strong">IQUIQUE EXPRESS</td>
                <td class="strong">2636E</td>
              </tr>
              <tr>
                <td>Vessel arrived</td>
                <td>MANZANILLO, COL (MX)</td>
                <td>2026-10-26</td>
                <td>08:00</td>
                <td>IQUIQUE EXPRESS</td>
                <td>2636E</td>
              </tr>
              <tr>
                <td>Discharged</td>
                <td>MANZANILLO, COL (MX)</td>
                <td>2026-10-22</td>
                <td>20:38</td>
                <td>IQUIQUE EXPRESS</td>
                <td>2636E</td>
              </tr>
              <tr>
                <td>Something unknown</td>
                <td>MANZANILLO, COL (MX)</td>
                <td>2026-10-22</td>
                <td>20:38</td>
                <td>Truck</td>
                <td></td>
              </tr>
            </tbody></table>
            """;

    @Test
    @DisplayName("Una fila con la clase strong es un movimiento confirmado")
    void strongRowIsConfirmed() {
        HapagLloydMovement movement = ChromeHapagLloydTrackingClient.parseMovementRow(
                rowWithStatus("Vessel arrived", true));

        assertThat(movement).isNotNull();
        assertThat(movement.isActual()).isTrue();
        assertThat(movement.getStatus()).isEqualTo("Vessel arrived");
        assertThat(movement.getPlace()).isEqualTo("MANZANILLO, COL (MX)");
        assertThat(movement.getDate()).isEqualTo("2026-10-21");
        assertThat(movement.getTime()).isEqualTo("08:00");
        assertThat(movement.getTransport()).isEqualTo("IQUIQUE EXPRESS");
        assertThat(movement.getVoyageNumber()).isEqualTo("2636E");
    }

    @Test
    @DisplayName("La misma fila sin strong es un hito planificado, no confirmado")
    void plainRowIsNotConfirmed() {
        HapagLloydMovement movement = ChromeHapagLloydTrackingClient.parseMovementRow(
                rowWithStatus("Vessel arrived", false));

        assertThat(movement).isNotNull();
        assertThat(movement.getStatus()).isEqualTo("Vessel arrived");
        assertThat(movement.isActual()).isFalse();
        assertThat(movement.getDate()).isEqualTo("2026-10-26");
    }

    @Test
    @DisplayName("Una fila con estado desconocido no es un movimiento")
    void unknownStatusRowIsIgnored() {
        assertThat(ChromeHapagLloydTrackingClient.parseMovementRow(
                rowContaining("Something unknown"))).isNull();
    }

    @Test
    @DisplayName("Una fila de cabecera no es un movimiento")
    void headerRowIsIgnored() {
        assertThat(ChromeHapagLloydTrackingClient.parseMovementRow(
                detailRows().first())).isNull();
    }

    @Test
    @DisplayName("El marcador de confirmacion es la clase strong")
    void actualMarkerIsStrong() {
        assertThat(ChromeHapagLloydTrackingClient.ACTUAL_CLASS_MARKER).isEqualTo("strong");
    }

    private static Elements detailRows() {
        return Jsoup.parse(DETAIL_HTML).select("tr");
    }

    private static Element rowWithStatus(String status, boolean confirmed) {
        for (Element row : detailRows()) {
            Elements cells = row.select("td");
            if (cells.size() < 3 || !cells.get(0).text().trim().equals(status)) {
                continue;
            }
            if (cells.get(0).className().contains("strong") == confirmed) {
                return row;
            }
        }
        throw new AssertionError("fila no encontrada: " + status + " confirmed=" + confirmed);
    }

    private static Element rowContaining(String text) {
        for (Element row : detailRows()) {
            if (row.text().contains(text)) {
                return row;
            }
        }
        throw new AssertionError("fila no encontrada con: " + text);
    }

    private static List<String> argumentosConPrefijo(List<String> command, String prefix) {
        return command.stream().filter(arg -> arg.startsWith(prefix)).toList();
    }
}
