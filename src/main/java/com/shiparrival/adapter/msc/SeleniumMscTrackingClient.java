package com.shiparrival.adapter.msc;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente de rastreo de MSC basado en un navegador Chrome headless.
 *
 * <p>El portal de MSC está protegido por Akamai y bloquea las peticiones
 * HTTP directas con un 403. Un navegador real sí obtiene las cookies de
 * seguridad necesarias, por lo que se abre la página de rastreo y desde
 * su propio contexto se invoca la API interna de MSC.</p>
 */
@Slf4j
@Component
public class SeleniumMscTrackingClient implements MscTrackingClient {

    private static final String TRACKING_URL = "https://www.msc.com/en/track-a-shipment";
    private static final String TRACKING_API = "/api/feature/tools/TrackingInfo";
    private static final String FETCH_ERROR_PREFIX = "__MSC_FETCH_ERROR__:";
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final long AKAMAI_COOKIE_WAIT_MS = 4000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/132.0.0.0 Safari/537.36";

    private final Object monitor = new Object();
    private ChromeDriver driver;

    @Override
    public String fetchTrackingJson(String trackingNumber) {
        synchronized (monitor) {
            ensureDriverStarted();

            try {
                String response = executeFetch(trackingNumber);
                if (!isBlockedOrEmpty(response)) {
                    return response;
                }
                log.warn("MSC devolvió una respuesta vacía o una página de bloqueo; se reinicia la sesión del navegador");
            } catch (MscTrackingException ex) {
                log.warn("Error al consultar MSC; se reinicia la sesión del navegador: {}", ex.getMessage());
            }

            // Un único reintento con una sesión nueva: las cookies de Akamai
            // pueden haber caducado o el navegador puede haber quedado inestable.
            restartDriver();
            return executeFetch(trackingNumber);
        }
    }

    private void ensureDriverStarted() {
        if (driver == null) {
            startDriver();
        }
    }

    private void startDriver() {
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

        try {
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
            driver.executeCdpCommand(
                    "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"));

            driver.get(TRACKING_URL);
            waitForPageReady();

            log.info("Navegador de MSC iniciado y página de rastreo cargada");
        } catch (RuntimeException ex) {
            closeDriver();
            throw new MscTrackingException(
                    "No se pudo iniciar el navegador para consultar MSC: " + ex.getMessage(), ex);
        }
    }

    private void waitForPageReady() {
        WebDriverWait wait = new WebDriverWait(driver, PAGE_LOAD_TIMEOUT);
        wait.until(d -> "complete".equals(
                ((JavascriptExecutor) d).executeScript("return document.readyState")));

        // Akamai ejecuta scripts que establecen cookies después del load.
        try {
            Thread.sleep(AKAMAI_COOKIE_WAIT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MscTrackingException("Consulta a MSC interrumpida", ex);
        }

        String title = driver.getTitle();
        if (title != null && title.contains("Access Denied")) {
            throw new MscTrackingException("MSC bloqueó el acceso del navegador: " + title);
        }
    }

    private String executeFetch(String trackingNumber) {
        try {
            Object raw = ((JavascriptExecutor) driver).executeAsyncScript(
                    """
                    const [number, done] = arguments;
                    fetch('%s', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-Requested-With': 'XMLHttpRequest',
                            'Accept': 'application/json, text/plain, */*'
                        },
                        body: JSON.stringify({trackingNumber: number, trackingMode: '0'})
                    })
                    .then(async response => done(await response.text()))
                    .catch(error => done('%s' + error));
                    """.formatted(TRACKING_API, FETCH_ERROR_PREFIX),
                    trackingNumber);

            if (raw == null) {
                throw new MscTrackingException("MSC no devolvió ninguna respuesta");
            }

            String text = raw.toString();
            if (text.startsWith(FETCH_ERROR_PREFIX)) {
                throw new MscTrackingException(
                        "Error de red al consultar MSC: " + text.substring(FETCH_ERROR_PREFIX.length()));
            }
            return text;
        } catch (MscTrackingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MscTrackingException(
                    "Error al consultar MSC desde el navegador: " + ex.getMessage(), ex);
        }
    }

    private boolean isBlockedOrEmpty(String response) {
        if (response == null || response.isBlank()) {
            return true;
        }
        String trimmed = response.trim();
        return trimmed.startsWith("<") || trimmed.contains("Access Denied");
    }

    private void restartDriver() {
        closeDriver();
        startDriver();
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (monitor) {
            closeDriver();
        }
    }

    private void closeDriver() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ex) {
                log.debug("Error al cerrar el navegador de MSC: {}", ex.getMessage());
            } finally {
                driver = null;
            }
        }
    }
}
