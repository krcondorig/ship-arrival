package com.shiparrival.adapter.hapaglloyd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Slf4j
public class ChromeHapagLloydTrackingClient implements HapagLloydTrackingClient {

    private static final String TRACKING_URL =
            "https://www.hapag-lloyd.com/en/online-business/track/track-by-booking-solution.html";
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

    private static final String MOVEMENTS_JS = """
            (() => {
              const table = document.getElementById('tracing_by_booking_f:hl66');
              if (!table) return '[]';
              const out = [];
              for (const row of table.querySelectorAll('tbody tr')) {
                const cells = row.querySelectorAll('td');
                if (cells.length < 6) continue;
                const status = (cells[0].textContent || '').trim();
                if (!status) continue;
                const actual = Array.from(cells).some(
                  c => (c.className || '').toString().toLowerCase().includes('strong')
                );
                out.push({
                  status,
                  place: (cells[1].textContent || '').trim(),
                  date: (cells[2].textContent || '').trim(),
                  time: (cells[3].textContent || '').trim(),
                  transport: (cells[4].textContent || '').trim(),
                  voyageNumber: (cells[5].textContent || '').trim(),
                  actual
                });
              }
              return JSON.stringify(out);
            })()
            """;

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
        boolean detailReady = false;
        HapagLloydTrackingException lastDetailError = null;

        for (int attempt = 1; attempt <= 2 && !detailReady; attempt++) {
            try {
                clickContainerAndDetails(first.containerNumber(), trackingNumber);
                detailReady = waitForDetailTable(trackingNumber);
            } catch (HapagLloydTrackingException ex) {
                lastDetailError = ex;
                log.debug("No se pudo abrir el detalle de {} (intento {}): {}",
                        first.containerNumber(), attempt, ex.getMessage());
            }
            if (!detailReady && attempt < 2) {
                sleepQuietly(1500L);
            }
        }

        if (!detailReady) {
            throw lastDetailError != null
                    ? lastDetailError
                    : new HapagLloydTrackingException(
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
        if (headless) {
            log.warn("Hapag-Lloyd está configurado en modo headless; Cloudflare suele bloquearlo. "
                    + "Se recomienda hapag-lloyd.headless=false (Chrome fuera de pantalla).");
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
        command.add(initialUrl);

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
                    && target.title().toLowerCase(Locale.ROOT).contains("tracing by booking")
                    && target.url() != null
                    && target.url().contains("blno=")) {
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
            if (safeEvaluateBoolean(DETAIL_EXISTS_JS)) {
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

    private List<HapagLloydMovement> parseMovements() {
        String json = cdp.evaluateString(MOVEMENTS_JS);
        try {
            JsonNode root = objectMapper.readTree(json);
            List<HapagLloydMovement> movements = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    movements.add(HapagLloydMovement.builder()
                            .status(node.path("status").asText(""))
                            .place(node.path("place").asText(""))
                            .date(node.path("date").asText(""))
                            .time(node.path("time").asText(""))
                            .transport(node.path("transport").asText(""))
                            .voyageNumber(node.path("voyageNumber").asText(""))
                            .actual(node.path("actual").asBoolean(false))
                            .build());
                }
            }
            return movements;
        } catch (JsonProcessingException ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo interpretar los movimientos de Hapag-Lloyd: "
                            + ex.getMessage(), ex);
        }
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
