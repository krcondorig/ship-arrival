package com.shiparrival.adapter.maersk;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente de rastreo de Maersk basado en Chrome headless.
 *
 * <p>El portal de Maersk está protegido por Akamai y bloquea las peticiones
 * HTTP directas con un 403. Además, Akamai solo permite <strong>una</strong>
 * consulta de rastreo por sesión de navegador: la segunda petición reutilizando
 * el mismo perfil devuelve 403. Por ese motivo se abre un navegador nuevo para
 * cada guía y se cierra inmediatamente después de capturar la respuesta.</p>
 *
 * <p>En lugar de replicar las cabeceras internas del portal (Consumer-Key,
 * API-Version y Akamai-BM-Telemetry), se inyecta un interceptor de
 * {@code window.fetch} antes de cargar la página. Así se captura el JSON que
 * la propia página solicita a su API, con toda la telemetría de Akamai
 * generada por el navegador.</p>
 */
@Slf4j
@Component
public class SeleniumMaerskTrackingClient implements MaerskTrackingClient {

    private static final String TRACKING_URL = "https://www.maersk.com/tracking/";
    private static final String RESPONSES_VAR = "window.__maerskTrackingResponses";
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(45);
    private static final long POLL_INTERVAL_MS = 500L;
    private static final long RETRY_DELAY_MS = 2000L;
    private static final int MAX_ATTEMPTS = 2;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/132.0.0.0 Safari/537.36";

    /**
     * Interceptor que guarda todas las respuestas de la API de rastreo de Maersk.
     * Se almacenan en un arreglo porque la página puede hacer más de un intento.
     */
    private static final String FETCH_INTERCEPTOR = """
            (function () {
                if (window.__maerskInterceptorInstalled) {
                    return;
                }
                window.__maerskInterceptorInstalled = true;
                window.__maerskTrackingResponses = [];
                const originalFetch = window.fetch;
                window.fetch = async function (...args) {
                    const response = await originalFetch.apply(this, args);
                    try {
                        const url = typeof args[0] === 'string'
                            ? args[0]
                            : (args[0] && args[0].url) || '';
                        if (url.indexOf('/synergy/tracking/') !== -1) {
                            const clone = response.clone();
                            clone.text().then(text => {
                                window.__maerskTrackingResponses.push({
                                    status: response.status,
                                    body: text
                                });
                            }).catch(() => {});
                        }
                    } catch (e) {
                        // El interceptor nunca debe romper la página.
                    }
                    return response;
                };
            })();
            """;

    private static final String ANTI_DETECTION_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});";

    interface BrowserSession extends AutoCloseable {
        String fetch(String trackingNumber);

        @Override
        void close();
    }

    @FunctionalInterface
    interface BrowserSessionFactory {
        BrowserSession open();
    }

    private final BrowserSessionFactory browserSessionFactory;

    public SeleniumMaerskTrackingClient() {
        this(null);
    }

    SeleniumMaerskTrackingClient(BrowserSessionFactory browserSessionFactory) {
        this.browserSessionFactory = browserSessionFactory == null ? this::openChromeSession : browserSessionFactory;
    }

    @Override
    public String fetchTrackingJson(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new MaerskTrackingException("El número de guía no puede estar vacío");
        }

        MaerskTrackingException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = fetchWithFreshBrowser(trackingNumber);
                if (!isBlockedOrEmpty(response)) {
                    return response;
                }
                lastError = new MaerskTrackingException(
                        "Maersk devolvió una página de bloqueo en lugar de datos");
                log.warn("Maersk devolvió una página de bloqueo para la guía {} (intento {}/{})",
                        trackingNumber, attempt, MAX_ATTEMPTS);
            } catch (MaerskTrackingException ex) {
                lastError = ex;
                log.warn("Error al consultar Maersk para la guía {} (intento {}/{}): {}",
                        trackingNumber, attempt, MAX_ATTEMPTS, ex.getMessage());
            }

            if (attempt < MAX_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }

        throw lastError != null
                ? lastError
                : new MaerskTrackingException("No se pudo consultar Maersk para la guía " + trackingNumber);
    }

    private String fetchWithFreshBrowser(String trackingNumber) {
        BrowserSession session = null;
        try {
            session = browserSessionFactory.open();
            return session.fetch(trackingNumber);
        } catch (MaerskTrackingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MaerskTrackingException(
                    "Error al consultar Maersk desde el navegador: " + ex.getMessage(), ex);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private BrowserSession openChromeSession() {
        ChromeDriver driver = createDriver();
        return new BrowserSession() {
            @Override
            public String fetch(String trackingNumber) {
                String url = TRACKING_URL + trackingNumber;
                log.debug("Cargando página de rastreo de Maersk: {}", url);
                driver.get(url);
                String response = waitForTrackingResponse(driver, trackingNumber);
                log.debug("Respuesta de Maersk capturada para la guía {} ({} caracteres)",
                        trackingNumber, response.length());
                return response;
            }

            @Override
            public void close() {
                quitDriver(driver);
            }
        };
    }

    private ChromeDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1920,1080",
                "--disable-blink-features=AutomationControlled",
                "--lang=en-US,en;q=0.9",
                "--user-agent=" + USER_AGENT
        );
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
        driver.executeCdpCommand(
                "Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", ANTI_DETECTION_SCRIPT));
        driver.executeCdpCommand(
                "Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", FETCH_INTERCEPTOR));
        return driver;
    }

    private String waitForTrackingResponse(ChromeDriver driver, String trackingNumber) {
        long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Object raw = ((JavascriptExecutor) driver).executeScript(
                        "return " + RESPONSES_VAR + " || [];");

                if (raw instanceof List<?> responses) {
                    String jsonResponse = findJsonResponse(responses);
                    if (jsonResponse != null) {
                        return jsonResponse;
                    }
                    String blockedResponse = findBlockedResponse(responses);
                    if (blockedResponse != null) {
                        return blockedResponse;
                    }
                }
            } catch (WebDriverException ex) {
                log.debug("La página de Maersk aún no está lista para la guía {}: {}",
                        trackingNumber, ex.getMessage());
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MaerskTrackingException("Consulta a Maersk interrumpida", ex);
            }
        }

        throw new MaerskTrackingException(
                "Maersk no devolvió la respuesta de rastreo a tiempo para la guía " + trackingNumber);
    }

    private String findJsonResponse(List<?> responses) {
        for (Object item : responses) {
            if (!(item instanceof Map<?, ?> response)) {
                continue;
            }
            Object body = response.get("body");
            if (body != null && body.toString().trim().startsWith("{")) {
                return body.toString();
            }
        }
        return null;
    }

    private String findBlockedResponse(List<?> responses) {
        for (Object item : responses) {
            if (!(item instanceof Map<?, ?> response)) {
                continue;
            }
            Object body = response.get("body");
            if (body == null) {
                continue;
            }
            String text = body.toString().trim();
            if (text.startsWith("<") || text.contains("Access Denied")) {
                return text;
            }
        }
        return null;
    }

    private boolean isBlockedOrEmpty(String response) {
        if (response == null || response.isBlank()) {
            return true;
        }
        String trimmed = response.trim();
        return trimmed.startsWith("<") || trimmed.contains("Access Denied");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MaerskTrackingException("Reintento de Maersk interrumpido", ex);
        }
    }

    private void quitDriver(ChromeDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (RuntimeException ex) {
            log.debug("Error al cerrar el navegador de Maersk: {}", ex.getMessage());
        }
    }

    @Override
    public void close() {
        // Cada consulta abre y cierra su propio navegador, por lo que no hay
        // una sesión persistente que liberar al finalizar el ciclo.
    }
}
