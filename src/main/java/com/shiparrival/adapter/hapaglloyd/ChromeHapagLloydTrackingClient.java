package com.shiparrival.adapter.hapaglloyd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Cliente de rastreo de Hapag-Lloyd basado en un Chrome normal controlado por
 * el Chrome DevTools Protocol (CDP).
 *
 * <p>El portal de Hapag-Lloyd está protegido por Cloudflare. Las peticiones
 * HTTP directas y Chrome headless reciben un desafío. Adjuntar ChromeDriver
 * después de superar el desafío también puede provocar uno nuevo, porque la
 * sesión pasa a estar marcada como automatizada. Por eso se lanza un Chrome
 * normal fuera de pantalla con un perfil persistente y se evalúa JavaScript
 * directamente por CDP, sin ChromeDriver.</p>
 *
 * <p>El navegador se lanza con la primera URL de guía para que Cloudflare se
 * resuelva antes de conectarse. Luego se reutiliza durante el ciclo y se cierra
 * al finalizar mediante {@link #close()}.</p>
 *
 * <p>Se activa solo con {@code hapag-lloyd.client=chrome}. El dashboard de Render
 * no define esa propiedad ni tiene Chrome instalado, asi que ahi sigue vigente el
 * cliente de ZenRows; el runner de GitHub Actions la activa junto con el proxy
 * SOCKS5 de WARP.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hapag-lloyd.client", havingValue = "chrome")
public class ChromeHapagLloydTrackingClient implements HapagLloydTrackingClient {

    /**
     * Listado del B/L en la app legacy. El sufijo {@code -legacy} es obligatorio:
     * sin él el portal redirige a la SPA nueva ({@code /solutions/tracking/}) y la
     * espera de título/URL de {@code waitForPageReady} nunca acierta.
     */
    private static final String TRACKING_URL =
            "https://www.hapag-lloyd.com/en/online-business/track/track-by-booking-solution-legacy.html";

    /**
     * URL de detalle por contenedor del portal legacy. Es el camino que se midió
     * funcionando con WARP: la sonda fue directo a esta página (sin pasar por el
     * listado del B/L, sin radio y sin botón {@code Details}) y obtuvo 8 estados
     * y 14 filas en ~1.9 s.
     */
    private static final String CONTAINER_DETAIL_URL =
            "https://www.hapag-lloyd.com/en/online-business/track/"
                    + "track-by-container-solution-legacy.html?view=S8510&container=";
    private static final String RESULTS_TABLE_ID = "tracing_by_booking_f:hl27";
    private static final String DETAILS_BUTTON_ID = "tracing_by_booking_f:hl27:hl53";
    private static final String DETAIL_TABLE_ID = "tracing_by_booking_f:hl66";

    private static final String RESULTS_EXISTS_JS =
            "document.getElementById('" + RESULTS_TABLE_ID + "') !== null";
    private static final String DETAIL_EXISTS_JS =
            "document.getElementById('" + DETAIL_TABLE_ID + "') !== null";
    private static final String TITLE_JS = "document.title || ''";
    private static final String BODY_TEXT_JS =
            "document.body ? document.body.innerText : ''";

    private static final String CONTAINER_ROWS_JS = """
            (() => {
              const table = document.getElementById('tracing_by_booking_f:hl27');
              if (!table) return '[]';
              const out = [];
              for (const row of table.querySelectorAll('tbody tr')) {
                const cells = row.querySelectorAll('td');
                if (cells.length < 6) continue;
                const container = (cells[2].textContent || '').trim();
                if (!/^[A-Z]{4}\\s*\\d{7}$/.test(container)) continue;
                out.push({
                  containerType: (cells[1].textContent || '').trim(),
                  containerNumber: container,
                  status: (cells[3].textContent || '').trim(),
                  date: (cells[4].textContent || '').trim(),
                  place: (cells[5].textContent || '').trim()
                });
              }
              return JSON.stringify(out);
            })()
            """;

    private static final String DETAIL_TEXT_JS =
            "document.body ? document.body.innerText : ''";

    /**
     * Clase CSS con la que el portal marca los hitos confirmados.
     *
     * <p>{@link HapagLloydMovement#isActual()} depende de esta marca: los
     * movimientos confirmados salen en negrita y los planificados no. Si el
     * portal cambiara de marca, el arribo real dejaria de detectarse y la guia
     * nunca pasaria a {@code ARRIVED}. Por eso el valor vive aqui, en Java, y no
     * dentro de un script: asi un test puede custodiarlo.</p>
     */
    static final String ACTUAL_CLASS_MARKER = "strong";

    /**
     * Script que devuelve el HTML de cada fila de la pagina de detalle.
     *
     * <p>No interpreta nada: solo serializa las filas. La decision de que fila es
     * un movimiento y de cual esta confirmada se toma en Java, en
     * {@link #parseMovementRow(org.jsoup.nodes.Element)}, para que sea testeable
     * sin abrir un navegador.</p>
     */
    private static final String MOVEMENT_ROWS_JS =
            "JSON.stringify(Array.from(document.querySelectorAll('tr'))"
                    + ".map(r => r.outerHTML))";

    /** Estados que emite la tabla de movimientos de Hapag-Lloyd, en su texto exacto. */
    private static final List<String> HAPAG_STATES = List.of(
            "gate out empty", "gate in empty", "gate out full", "gate in full",
            "arrival in", "departure from", "loaded", "discharged",
            "vessel departed", "vessel arrived");

    private static final String CLICK_DETAILS_JS = """
            (() => {
              const button = document.getElementById('tracing_by_booking_f:hl27:hl53');
              if (button) {
                button.click();
                return true;
              }
              const candidates = Array.from(
                document.querySelectorAll('button, input[type=submit], a')
              );
              const found = candidates.find(
                el => ((el.textContent || el.value || '').trim() === 'Details')
              );
              if (found) {
                found.click();
                return true;
              }
              return false;
            })()
            """;

    private static final String DEFAULT_PROFILE_DIR = "./data/hapag-chrome-profile";
    private static final int DEFAULT_PAGE_TIMEOUT_SECONDS = 120;
    private static final Duration DEVTOOLS_TIMEOUT = Duration.ofSeconds(20);
    private static final long POLL_INTERVAL_MS = 500L;
    private static final long RETRY_DELAY_MS = 2500L;
    private static final long CHALLENGE_TIMEOUT_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${hapag-lloyd.chrome.path:}")
    private String configuredChromePath;

    @Value("${hapag-lloyd.profile-dir:" + DEFAULT_PROFILE_DIR + "}")
    private String configuredProfileDir;

    @Value("${hapag-lloyd.remote-debug-port:0}")
    private int configuredDebugPort;

    @Value("${hapag-lloyd.page-timeout-seconds:" + DEFAULT_PAGE_TIMEOUT_SECONDS + "}")
    private int configuredPageTimeoutSeconds;

    @Value("${hapag-lloyd.headless:false}")
    private boolean headless;

    /**
     * Proxy del navegador, por ejemplo {@code socks5://127.0.0.1:40000}.
     * Vacío significa sin proxy, que es el comportamiento por defecto.
     */
    @Value("${hapag-lloyd.proxy:}")
    private String configuredProxy;

    private Process chromeProcess;
    private int debugPort;
    private CdpClient cdp;

    @Override
    public synchronized HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new HapagLloydTrackingException("El número de guía no puede estar vacío");
        }

        String cleanTrackingNumber = trackingNumber.trim();
        HapagLloydTrackingException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetchWithBrowser(cleanTrackingNumber);
            } catch (HapagLloydTrackingException ex) {
                lastError = ex;
                log.warn("Error al consultar Hapag-Lloyd para la guía {} (intento {}/{}): {}",
                        cleanTrackingNumber, attempt, MAX_ATTEMPTS, ex.getMessage());
            } catch (RuntimeException ex) {
                lastError = new HapagLloydTrackingException(
                        "Error al consultar Hapag-Lloyd desde el navegador: " + ex.getMessage(), ex);
                log.warn("Error inesperado al consultar Hapag-Lloyd para la guía {} (intento {}/{}): {}",
                        cleanTrackingNumber, attempt, MAX_ATTEMPTS, ex.getMessage());
            }

            closeBrowser();
            if (attempt < MAX_ATTEMPTS) {
                sleepQuietly(RETRY_DELAY_MS);
            }
        }

        throw lastError != null
                ? lastError
                : new HapagLloydTrackingException(
                        "No se pudo consultar Hapag-Lloyd para la guía " + cleanTrackingNumber);
    }

    private HapagLloydTrackingSnapshot fetchWithBrowser(String trackingNumber) {
        String url = TRACKING_URL + "?blno="
                + URLEncoder.encode(trackingNumber, StandardCharsets.UTF_8);
        boolean launched = ensureBrowser(url);

        if (!launched) {
            log.debug("Navegando a Hapag-Lloyd: {}", url);
            try {
                cdp.navigate(url);
            } catch (HapagLloydTrackingException ex) {
                log.debug("La navegación de Hapag-Lloyd interrumpió el contexto CDP: {}",
                        ex.getMessage());
            }
            waitForPageReady(debugPort);
            ensureCdpConnected();
        }

        waitForResults(trackingNumber);
        sleepQuietly(1000L);
        waitForResults(trackingNumber);

        List<ContainerRow> containers = parseContainerRows();
        if (containers.isEmpty()) {
            throw new HapagLloydTrackingException(
                    "Hapag-Lloyd no devolvió contenedores para la guía " + trackingNumber);
        }

        ContainerRow first = containers.get(0);

        // Camino medido con WARP: se va directo a la página de detalle legacy por
        // contenedor en lugar de marcar el radio y pulsar el botón Details, que es
        // la mitad que fallaba. El número viaja tal como lo devuelve el listado.
        String detailUrl = buildContainerDetailUrl(first.containerNumber());
        log.debug("Navegando al detalle de Hapag-Lloyd: {}", detailUrl);
        try {
            cdp.navigate(detailUrl);
        } catch (HapagLloydTrackingException ex) {
            log.debug("La navegación del detalle de Hapag-Lloyd interrumpió el contexto CDP: {}",
                    ex.getMessage());
        }
        waitForPageReady(debugPort, "tracing by", "container=");
        ensureCdpConnected();

        if (!waitForDetailTable(trackingNumber)) {
            throw new HapagLloydTrackingException(
                    "No se pudo abrir el detalle del contenedor "
                            + first.containerNumber());
        }

        List<HapagLloydMovement> movements = parseMovements();
        if (movements.isEmpty()) {
            throw new HapagLloydTrackingException(
                    "Hapag-Lloyd no devolvió movimientos para la guía " + trackingNumber);
        }

        HapagLloydContainer container = HapagLloydContainer.builder()
                .containerNumber(first.containerNumber())
                .containerType(first.containerType())
                .status(first.status())
                .date(first.date())
                .place(first.place())
                .movements(movements)
                .build();

        log.debug("Hapag-Lloyd devolvió {} movimientos para la guía {} (contenedor {})",
                movements.size(), trackingNumber, first.containerNumber());

        return HapagLloydTrackingSnapshot.builder()
                .trackingNumber(trackingNumber)
                .containers(List.of(container))
                .build();
    }

    private boolean ensureBrowser(String initialUrl) {
        if (cdp != null && cdp.isOpen()) {
            return false;
        }
        closeBrowser();
        launchBrowser(initialUrl);
        return true;
    }

    private void launchBrowser(String initialUrl) {
        boolean headlessMode = resolveHeadless();
        if (headlessMode) {
            log.debug("Hapag-Lloyd se lanza en modo headless (sin ventana).");
        }

        Path profilePath = profilePath();
        try {
            Files.createDirectories(profilePath);
        } catch (IOException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo crear el perfil de Chrome de Hapag-Lloyd en " + profilePath, ex);
        }

        closeOrphanChromeProcesses(profilePath);

        debugPort = configuredDebugPort > 0 ? configuredDebugPort : findFreePort();
        String chromePath = resolveChromePath();
        String proxy = resolveProxy();

        List<String> command = buildChromeCommand(
                chromePath, profilePath, debugPort, headlessMode, proxy, initialUrl);

        Path logFile = Path.of("data", "hapag-chrome.log").toAbsolutePath();
        try {
            Files.createDirectories(logFile.getParent());
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            chromeProcess = processBuilder.start();
        } catch (IOException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo iniciar Chrome para Hapag-Lloyd. Ruta usada: " + chromePath
                            + ". Configure hapag-lloyd.chrome.path o HAPAG_LLOYD_CHROME_PATH. "
                            + "Detalle: " + ex.getMessage(), ex);
        }

        log.debug("Chrome de Hapag-Lloyd iniciado (PID {}, puerto CDP {}, perfil {})",
                chromeProcess.pid(), debugPort, profilePath);
        waitForDevTools(debugPort);
        connectCdp();
        waitForPageReady(debugPort);
    }

    /**
     * Construye la lista de argumentos con los que se lanza Chrome.
     *
     * <p>Es estático y package-private para poder verificarlo sin abrir un
     * navegador. Los flags conservan el orden histórico del cliente; el proxy,
     * cuando existe, se inserta justo antes de la URL inicial.</p>
     */
    static List<String> buildChromeCommand(String chromePath,
                                          Path profilePath,
                                          int debugPort,
                                          boolean headless,
                                          String proxy,
                                          String initialUrl) {
        List<String> command = new ArrayList<>();
        command.add(chromePath);
        command.add("--remote-debugging-port=" + debugPort);
        command.add("--remote-allow-origins=*");
        command.add("--user-data-dir=" + profilePath);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-features=Translate");
        command.add("--disable-blink-features=AutomationControlled");
        command.add("--disable-background-timer-throttling");
        command.add("--disable-backgrounding-occluded-windows");
        command.add("--disable-renderer-backgrounding");
        command.add("--window-position=0,0");
        command.add("--window-size=1280,900");
        command.add("--lang=en-US,en;q=0.9");
        if (headless) {
            command.add("--headless=new");
        }
        if (proxy != null && !proxy.isBlank()) {
            command.add("--proxy-server=" + proxy.trim());
        }
        command.add(initialUrl);
        return command;
    }

    private void connectCdp() {
        PageTarget target = readPageTarget(debugPort);
        if (target == null
                || target.webSocketDebuggerUrl() == null
                || target.webSocketDebuggerUrl().isBlank()) {
            throw new HapagLloydTrackingException(
                    "No se encontró la pestaña de Hapag-Lloyd para conectar por CDP");
        }
        cdp = new CdpClient();
        cdp.connect(URI.create(target.webSocketDebuggerUrl()));
    }

    private void ensureCdpConnected() {
        if (cdp != null && cdp.isOpen()) {
            return;
        }
        if (cdp != null) {
            cdp.close();
        }
        connectCdp();
    }

    private void waitForDevTools(int port) {
        long deadline = System.currentTimeMillis() + DEVTOOLS_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (chromeProcess != null && !chromeProcess.isAlive()) {
                throw new HapagLloydTrackingException(
                        "Chrome se cerró antes de exponer el puerto de depuración. "
                                + "Revise data/hapag-chrome.log");
            }
            HttpURLConnection connection = null;
            try {
                URL url = URI.create("http://127.0.0.1:" + port + "/json/version").toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                int status = connection.getResponseCode();
                try (InputStream in = connection.getInputStream()) {
                    in.readAllBytes();
                }
                if (status == 200) {
                    return;
                }
            } catch (IOException ex) {
                log.trace("El puerto CDP {} todavía no responde: {}", port, ex.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            sleepQuietly(250L);
        }
        throw new HapagLloydTrackingException(
                "Chrome no expuso el puerto de depuración " + port + " a tiempo. "
                        + "Revise data/hapag-chrome.log");
    }

    /**
     * Espera a que Chrome, lanzado directamente con la URL de la guía, supere
     * el desafío de Cloudflare. Solo después se conecta el CDP. Se exige el
     * título final y que la URL conserve el parámetro {@code blno}; de lo
     * contrario la página puede estar en una transición y perder la guía.
     */
    private void waitForPageReady(int port) {
        waitForPageReady(port, "tracing by booking", "blno=");
    }

    /**
     * Espera a que la pestaña llegue al título y a la URL indicados. La variante
     * sin argumentos espera el listado por B/L ({@code blno=}); la navegación
     * directa al detalle legacy espera {@code container=}.
     */
    private void waitForPageReady(int port, String titleFragment, String urlFragment) {
        long deadline = System.currentTimeMillis() + pageTimeout();
        PageTarget lastTarget = null;
        long lastInteractionAttempt = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (chromeProcess != null && !chromeProcess.isAlive()) {
                throw new HapagLloydTrackingException(
                        "Chrome se cerró antes de cargar la página de Hapag-Lloyd. "
                                + "Revise data/hapag-chrome.log");
            }

            PageTarget target = readPageTarget(port);
            if (target != null) {
                lastTarget = target;
            }
            if (target != null
                    && target.title() != null
                    && target.title().toLowerCase(Locale.ROOT).contains(titleFragment)
                    && target.url() != null
                    && target.url().contains(urlFragment)) {
                log.debug("Página de Hapag-Lloyd lista (título: {})", target.title());
                return;
            }

            // Si Cloudflare muestra "Just a moment...", intentamos simular interacción periódicamente
            if (target != null
                    && target.title() != null
                    && target.title().toLowerCase(Locale.ROOT).contains("just a moment")
                    && System.currentTimeMillis() - lastInteractionAttempt > 5000L) {
                lastInteractionAttempt = System.currentTimeMillis();
                try {
                    if (cdp != null && cdp.isOpen()) {
                        cdp.evaluateBoolean("(() => { const iframe = document.querySelector('iframe'); if (iframe) { iframe.click(); return true; } document.body.click(); return false; })()");
                    }
                } catch (Exception ignored) {}
            }

            sleepQuietly(POLL_INTERVAL_MS);
        }

        String pageState = pageStateForDiagnostics(
                lastTarget == null ? "<sin pestaña Hapag-Lloyd>" : lastTarget.title(),
                lastTarget == null ? "" : lastTarget.url());
        log.warn("Tiempo de espera al cargar Hapag-Lloyd; estado observado: {}", pageState);
        throw new HapagLloydTrackingException(
                "Cloudflare no liberó la página de Hapag-Lloyd a tiempo. "
                        + "Estado observado: " + pageState);
    }

    /**
     * Compone la URL del detalle legacy por contenedor, el camino medido con WARP.
     *
     * <p>El sitio emite el número como cuatro letras, dos espacios y siete dígitos
     * ({@code HLBU  2959982}) y envía cada espacio como {@code +}, de modo que el
     * parámetro queda {@code container=HLBU++2959982}. Se rellena a esa forma
     * aunque el listado devuelva el número compacto, porque la variante compacta
     * no se comprobó contra el portal.</p>
     */
    static String buildContainerDetailUrl(String containerNumber) {
        return CONTAINER_DETAIL_URL + containerParam(paddedContainer(containerNumber));
    }

    /** Deja solo letras y dígitos: {@code HLBU++2959982} -> {@code HLBU2959982}. */
    static String normalizeContainer(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Devuelve el número con el relleno que emite el sitio: {@code HLBU  2959982}.
     * Si no tiene los 11 caracteres esperados se deja como está.
     */
    static String paddedContainer(String raw) {
        String number = normalizeContainer(raw);
        if (number.length() != 11) {
            return number;
        }
        return number.substring(0, 4) + "  " + number.substring(4);
    }

    /**
     * Traduce el texto a la forma que envía el sitio: cada espacio se vuelve
     * {@code +}. No se usa {@code URLEncoder} porque escaparía el {@code +} a
     * {@code %2B} y el servidor no encontraría el contenedor.
     */
    static String containerParam(String raw) {
        return raw == null ? "" : raw.trim().replace(" ", "+");
    }

    static String pageStateForDiagnostics(String title, String url) {
        String safeTitle = title == null ? "" : title.replaceAll("[\\r\\n\\t]", " ").trim();
        String safeUrl = "";
        if (url != null && !url.isBlank()) {
            try {
                URI parsed = URI.create(url);
                safeUrl = new URI(parsed.getScheme(), parsed.getAuthority(), parsed.getPath(), null, null)
                        .toString();
            } catch (Exception ignored) {
                safeUrl = "<URL no válida>";
            }
        }
        return "title='" + safeTitle + "', url='" + safeUrl + "'";
    }

    private PageTarget readPageTarget(int port) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create("http://127.0.0.1:" + port + "/json").toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(2000);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                if (root != null && root.isArray()) {
                    for (JsonNode target : root) {
                        if ("page".equals(target.path("type").asText())
                                && target.path("url").asText("").contains("hapag-lloyd.com")) {
                            return new PageTarget(
                                    target.path("title").asText(""),
                                    target.path("url").asText(""),
                                    target.path("webSocketDebuggerUrl").asText(""));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            log.trace("No se pudo leer el estado de la página de Hapag-Lloyd: {}",
                    ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private void waitForResults(String trackingNumber) {
        long deadline = System.currentTimeMillis() + pageTimeout();
        long challengeSince = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (safeEvaluateBoolean(RESULTS_EXISTS_JS)) {
                return;
            }

            String body = safeBodyText();
            checkForTrackingError(trackingNumber, body);

            if (isChallengePage(body)) {
                if (challengeSince == 0L) {
                    challengeSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - challengeSince > CHALLENGE_TIMEOUT_MS) {
                    throw new HapagLloydTrackingException(
                            buildTimeoutMessage("resultados", trackingNumber));
                }
            } else {
                challengeSince = 0L;
            }

            sleepQuietly(POLL_INTERVAL_MS);
        }
        throw new HapagLloydTrackingException(
                buildTimeoutMessage("resultados", trackingNumber));
    }

    private List<ContainerRow> parseContainerRows() {
        String json = cdp.evaluateString(CONTAINER_ROWS_JS);
        try {
            JsonNode root = objectMapper.readTree(json);
            List<ContainerRow> containers = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    containers.add(new ContainerRow(
                            node.path("containerType").asText(""),
                            node.path("containerNumber").asText(""),
                            node.path("status").asText(""),
                            node.path("date").asText(""),
                            node.path("place").asText("")));
                }
            }
            return containers;
        } catch (JsonProcessingException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo interpretar la tabla de contenedores de Hapag-Lloyd: "
                            + ex.getMessage(), ex);
        }
    }

    private void clickContainerAndDetails(String containerNumber, String trackingNumber) {
        waitForResults(trackingNumber);
        boolean selected = cdp.evaluateBoolean(buildClickRadioJs(containerNumber));
        if (!selected) {
            throw new HapagLloydTrackingException(
                    "No se pudo seleccionar el contenedor " + containerNumber
                            + " en Hapag-Lloyd");
        }
        sleepQuietly(500L);

        boolean clicked;
        try {
            clicked = cdp.evaluateBoolean(CLICK_DETAILS_JS);
        } catch (HapagLloydTrackingException ex) {
            log.debug("La navegación del detalle de Hapag-Lloyd destruyó el contexto CDP: {}",
                    ex.getMessage());
            clicked = true;
        }
        if (!clicked) {
            throw new HapagLloydTrackingException(
                    "No se encontró el botón Details de Hapag-Lloyd");
        }
    }

    private String buildClickRadioJs(String containerNumber) {
        return """
                (() => {
                  const table = document.getElementById('tracing_by_booking_f:hl27');
                  if (!table) return false;
                  const normalize = value => (value || '').replace(/\\s+/g, '').trim();
                  const rows = Array.from(table.querySelectorAll('tbody tr'));
                  let target = null;
                  for (const row of rows) {
                    const cells = row.querySelectorAll('td');
                    if (cells.length >= 6
                        && normalize(cells[2].textContent) === normalize('%s')) {
                      target = row;
                      break;
                    }
                  }
                  if (!target) {
                    for (const row of rows) {
                      const cells = row.querySelectorAll('td');
                      if (cells.length >= 6
                          && /^[A-Z]{4}\\s*\\d{7}$/.test((cells[2].textContent || '').trim())) {
                        target = row;
                        break;
                      }
                    }
                  }
                  if (!target) return false;
                  const radio = target.querySelector('div.hl-radio');
                  if (!radio) return false;
                  radio.click();
                  return true;
                })()
                """.formatted(containerNumber);
    }

    private boolean waitForDetailTable(String trackingNumber) {
        long deadline = System.currentTimeMillis() + pageTimeout();
        long challengeSince = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (isRealDetailText(safeBodyText())) {
                return true;
            }

            String body = safeBodyText();
            if (body.toLowerCase(Locale.ROOT).contains("please select a row")) {
                return false;
            }

            if (isChallengePage(body)) {
                if (challengeSince == 0L) {
                    challengeSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - challengeSince > CHALLENGE_TIMEOUT_MS) {
                    throw new HapagLloydTrackingException(
                            buildTimeoutMessage("detalles del contenedor", trackingNumber));
                }
            } else {
                challengeSince = 0L;
            }

            sleepQuietly(POLL_INTERVAL_MS);
        }
        throw new HapagLloydTrackingException(
                buildTimeoutMessage("detalles del contenedor", trackingNumber));
    }

    /**
     * ¿El texto corresponde a un detalle real y no a la shell de la SPA nueva?
     *
     * <p>Se cuentan estados de la tabla en lugar de buscar un id de componente:
     * el detalle legacy es otra vista JSF y no comparte los client id del
     * listado. Un detalle real trae al menos dos estados.</p>
     */
    static boolean isRealDetailText(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return false;
        }
        String flat = bodyText.toLowerCase(Locale.ROOT);
        int found = 0;
        for (String state : HAPAG_STATES) {
            if (flat.contains(state)) {
                found++;
                if (found >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<HapagLloydMovement> parseMovements() {
        String json = cdp.evaluateString(MOVEMENT_ROWS_JS);
        try {
            JsonNode root = objectMapper.readTree(json);
            List<HapagLloydMovement> movements = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    String rowHtml = node.asText("");
                    Element row = Jsoup.parse(
                            "<table><tbody>" + rowHtml + "</tbody></table>").selectFirst("tr");
                    HapagLloydMovement movement = row == null ? null : parseMovementRow(row);
                    if (movement != null) {
                        movements.add(movement);
                    }
                }
            }
            return movements;
        } catch (JsonProcessingException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo interpretar los movimientos de Hapag-Lloyd: "
                            + ex.getMessage(), ex);
        }
    }

    /**
     * Convierte una fila del detalle en un movimiento, o {@code null} si la fila
     * no es un movimiento.
     *
     * <p>Se exige que la primera celda sea un estado conocido: asi se descartan
     * las filas de cabecera y las de maquetacion sin depender de un client id,
     * que el detalle legacy no comparte con el listado.</p>
     *
     * <p>{@code actual} sale de la clase {@link #ACTUAL_CLASS_MARKER} en
     * cualquiera de las celdas: es como el portal marca los hitos confirmados
     * frente a los planificados.</p>
     */
    static HapagLloydMovement parseMovementRow(Element row) {
        if (row == null) {
            return null;
        }
        Elements cells = row.select("td");
        if (cells.size() < 3) {
            return null;
        }
        String status = cellText(cells, 0);
        if (!isKnownState(status)) {
            return null;
        }
        return HapagLloydMovement.builder()
                .status(status)
                .place(cellText(cells, 1))
                .date(cellText(cells, 2))
                .time(cellText(cells, 3))
                .transport(cellText(cells, 4))
                .voyageNumber(cellText(cells, 5))
                .actual(isConfirmedRow(cells))
                .build();
    }

    private static String cellText(Elements cells, int index) {
        if (index >= cells.size()) {
            return "";
        }
        return cells.get(index).text().trim();
    }

    /**
     * ¿La fila esta marcada como confirmada?
     *
     * <p>El portal aplica la clase {@link #ACTUAL_CLASS_MARKER} a las celdas de
     * los hitos que ya ocurrieron. Se comprueba en toda la fila y no solo en una
     * celda porque la marca no siempre cae en la misma columna.</p>
     */
    static boolean isConfirmedRow(Elements cells) {
        for (Element cell : cells) {
            String classes = cell.className().toLowerCase(Locale.ROOT);
            if (classes.contains(ACTUAL_CLASS_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownState(String status) {
        if (status == null) {
            return false;
        }
        String lower = status.toLowerCase(Locale.ROOT);
        return HAPAG_STATES.contains(lower);
    }

    private boolean safeEvaluateBoolean(String expression) {
        try {
            if (cdp == null || !cdp.isOpen()) {
                ensureCdpConnected();
            }
            return cdp.evaluateBoolean(expression);
        } catch (HapagLloydTrackingException ex) {
            log.debug("No se pudo evaluar JavaScript en Hapag-Lloyd: {}", ex.getMessage());
            if (cdp != null && !cdp.isOpen()) {
                try {
                    ensureCdpConnected();
                } catch (HapagLloydTrackingException reconnectEx) {
                    log.debug("No se pudo reconectar CDP: {}", reconnectEx.getMessage());
                }
            }
            return false;
        }
    }

    private String safeTitle() {
        try {
            if (cdp == null || !cdp.isOpen()) {
                ensureCdpConnected();
            }
            return cdp.evaluateString(TITLE_JS);
        } catch (HapagLloydTrackingException ex) {
            return "";
        }
    }

    private String safeBodyText() {
        try {
            if (cdp == null || !cdp.isOpen()) {
                ensureCdpConnected();
            }
            return cdp.evaluateString(BODY_TEXT_JS);
        } catch (HapagLloydTrackingException ex) {
            return "";
        }
    }

    private boolean isChallengePage(String body) {
        String title = safeTitle();
        if (!title.isBlank() && isChallengeTitle(title)) {
            return true;
        }
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("challenges.cloudflare.com")
                || lower.contains("turnstile");
    }

    private boolean isChallengeTitle(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("un momento")
                || lower.contains("just a moment")
                || lower.contains("security check")
                || lower.contains("attention required")
                || lower.contains("please wait");
    }

    private void checkForTrackingError(String trackingNumber, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        boolean error = lower.contains("not found")
                || lower.contains("no data")
                || lower.contains("invalid")
                || lower.contains("no se encontraron")
                || lower.contains("no se ha encontrado")
                || lower.contains("does not exist")
                || lower.contains("no records")
                || lower.contains("no results");
        if (error) {
            throw new HapagLloydTrackingException(
                    "Hapag-Lloyd no encontró la guía " + trackingNumber + ": "
                            + snippet(body));
        }
    }

    private String buildTimeoutMessage(String what, String trackingNumber) {
        PageTarget target = readPageTarget(debugPort);
        String title = target != null ? target.title() : safeTitle();
        String url = target != null ? target.url() : "";
        String body = safeBodyText().toLowerCase(Locale.ROOT);
        boolean challenge = (title != null && isChallengeTitle(title))
                || body.contains("challenges.cloudflare.com")
                || body.contains("turnstile")
                || body.contains("security check");
        String message = "Hapag-Lloyd no devolvió la tabla de " + what
                + " a tiempo para la guía " + trackingNumber
                + ". Título: " + title + ". URL: " + url;
        if (challenge) {
            message += ". Cloudflare sigue mostrando un desafío. Abra Chrome con el perfil "
                    + profilePath()
                    + " y resuélvalo una vez; luego reintente el monitoreo.";
        }
        return message;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "...";
    }

    private void closeBrowser() {
        CdpClient currentCdp = cdp;
        cdp = null;
        if (currentCdp != null) {
            currentCdp.close();
        }

        Process currentProcess = chromeProcess;
        chromeProcess = null;
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
            try {
                if (!currentProcess.waitFor(3, TimeUnit.SECONDS)) {
                    currentProcess.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                currentProcess.destroyForcibly();
            }
        }
    }

    private void closeOrphanChromeProcesses(Path profilePath) {
        String marker = profilePath.toString();
        try {
            ProcessHandle.allProcesses()
                    .filter(process -> process.info().commandLine()
                            .map(commandLine -> commandLine.contains(marker))
                            .orElse(false))
                    .forEach(process -> {
                        log.info("Cerrando instancia previa de Chrome de Hapag-Lloyd (PID {})",
                                process.pid());
                        process.destroy();
                        try {
                            process.onExit().get(3, TimeUnit.SECONDS);
                        } catch (Exception ex) {
                            process.destroyForcibly();
                        }
                    });
        } catch (RuntimeException ex) {
            log.debug("No se pudieron limpiar procesos previos de Chrome: {}", ex.getMessage());
        }
    }

    private Path profilePath() {
        String value = configuredProfileDir == null || configuredProfileDir.isBlank()
                ? DEFAULT_PROFILE_DIR
                : configuredProfileDir;
        return Path.of(value).toAbsolutePath().normalize();
    }

    private int pageTimeoutSeconds() {
        return configuredPageTimeoutSeconds > 0
                ? configuredPageTimeoutSeconds
                : DEFAULT_PAGE_TIMEOUT_SECONDS;
    }

    private long pageTimeout() {
        return Duration.ofSeconds(pageTimeoutSeconds()).toMillis();
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo reservar un puerto local para Chrome", ex);
        }
    }

    /**
     * Resuelve el proxy del navegador con la misma precedencia que
     * {@link #resolveChromePath()}: primero el valor inyectado por Spring y,
     * si no hay, la variable de entorno {@code HAPAG_LLOYD_PROXY}. Así la
     * sonda que instancia el cliente sin Spring (y sin {@code @Value}) también
     * sale por el proxy cuando se le pasa por entorno.
     *
     * @return el proxy normalizado, o {@code null} si no hay proxy configurado
     */
    private String resolveProxy() {
        String configured = blankToNull(configuredProxy);
        if (configured != null) {
            return configured;
        }
        return blankToNull(System.getenv("HAPAG_LLOYD_PROXY"));
    }

    /**
     * ¿Se lanza Chrome sin ventana?
     *
     * <p>Delega en {@link #resolveHeadless(String, boolean)} pasándole el valor del
     * entorno {@code HAPAG_LLOYD_HEADLESS} y el campo inyectado por Spring. Así la
     * sonda, que instancia el cliente con {@code new} y no tiene {@code @Value},
     * también puede pedir headless por entorno; sin ese camino el flag quedaba en
     * {@code false} y Chrome arrancaba con ventana en un runner sin servidor X,
     * donde muere al instante.</p>
     */
    private boolean resolveHeadless() {
        return resolveHeadless(System.getenv("HAPAG_LLOYD_HEADLESS"), headless);
    }

    /**
     * Decide el modo headless a partir del valor del entorno y del valor de Spring.
     *
     * <p>La variable de entorno gana cuando está presente: el campo {@code headless}
     * solo tiene valor cuando hay Spring, y cuando no lo hay vale {@code false} sin
     * distinguir "no configurado" de "configurado en false".</p>
     */
    static boolean resolveHeadless(String envValue, boolean springValue) {
        if (envValue != null && !envValue.isBlank()) {
            return Boolean.parseBoolean(envValue.trim());
        }
        return springValue;
    }

    private String resolveChromePath() {
        String configured = blankToNull(configuredChromePath);
        if (configured != null && isExecutable(configured)) {
            return configured;
        }

        String fromEnv = blankToNull(System.getenv("HAPAG_LLOYD_CHROME_PATH"));
        if (fromEnv != null && isExecutable(fromEnv)) {
            return fromEnv;
        }

        List<String> candidates = new ArrayList<>();
        candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        candidates.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(localAppData + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        candidates.add("/usr/bin/google-chrome");
        candidates.add("/usr/bin/google-chrome-stable");
        candidates.add("/usr/bin/chromium");
        candidates.add("/usr/bin/chromium-browser");
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");

        for (String candidate : candidates) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "chrome.exe" : "google-chrome";
    }

    private boolean isExecutable(String path) {
        try {
            return Files.isExecutable(Path.of(path));
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HapagLloydTrackingException("Consulta a Hapag-Lloyd interrumpida", ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public synchronized void close() {
        closeBrowser();
    }

    private record PageTarget(String title, String url, String webSocketDebuggerUrl) {
    }

    private record ContainerRow(String containerType,
                                String containerNumber,
                                String status,
                                String date,
                                String place) {
    }
}
