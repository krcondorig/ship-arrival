package com.shiparrival.adapter.hapaglloyd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente mínimo del Chrome DevTools Protocol (CDP).
 *
 * <p>Permite evaluar JavaScript en una pestaña ya cargada por un Chrome normal,
 * sin adjuntar ChromeDriver. Así Cloudflare no detecta una sesión automatizada
 * y no vuelve a mostrar el desafío después de haberlo superado.</p>
 */
@Slf4j
public class CdpClient implements WebSocket.Listener, AutoCloseable {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final StringBuilder textBuffer = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile boolean closed;

    public void connect(URI uri) {
        try {
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .get(15, TimeUnit.SECONDS);
            closed = false;
            log.debug("Conectado al CDP de Hapag-Lloyd: {}", uri);
        } catch (Exception ex) {
            throw new HapagLloydTrackingException(
                    "No se pudo conectar al protocolo de depuración de Chrome: "
                            + ex.getMessage(), ex);
        }
    }

    public boolean isOpen() {
        WebSocket current = webSocket;
        return !closed && current != null && !current.isOutputClosed();
    }

    public JsonNode send(String method, ObjectNode params) {
        WebSocket current = webSocket;
        if (current == null || closed) {
            throw new HapagLloydTrackingException(
                    "La conexión CDP con Chrome no está abierta");
        }

        long id = nextId.incrementAndGet();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            String json = objectMapper.writeValueAsString(message);
            current.sendText(json, true).get(10, TimeUnit.SECONDS);
            JsonNode response = future.get(30, TimeUnit.SECONDS);
            if (response.has("error")) {
                throw new HapagLloydTrackingException(
                        "CDP devolvió un error en " + method + ": " + response.get("error"));
            }
            return response.path("result");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HapagLloydTrackingException("Consulta CDP interrumpida", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new HapagLloydTrackingException(
                    "No se recibió respuesta CDP para " + method + ": " + ex.getMessage(), ex);
        } catch (HapagLloydTrackingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new HapagLloydTrackingException(
                    "Error al enviar el comando CDP " + method + ": " + ex.getMessage(), ex);
        } finally {
            pending.remove(id);
        }
    }

    public JsonNode evaluate(String expression) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);
        JsonNode result = send("Runtime.evaluate", params);
        if (result.has("exceptionDetails")) {
            throw new HapagLloydTrackingException(
                    "Error JavaScript en Hapag-Lloyd: " + result.get("exceptionDetails"));
        }
        return result.path("result").path("value");
    }

    public boolean evaluateBoolean(String expression) {
        JsonNode value = evaluate(expression);
        return value.isBoolean() && value.asBoolean();
    }

    public String evaluateString(String expression) {
        JsonNode value = evaluate(expression);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    public void navigate(String url) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("url", url);
        send("Page.navigate", params);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        webSocket.request(1);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                          CharSequence data,
                                                          boolean last) {
        synchronized (textBuffer) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(message);
            }
        }
        webSocket.request(1);
        return null;
    }

    private void handleMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            JsonNode idNode = node.get("id");
            if (idNode != null && idNode.canConvertToLong()) {
                CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
                if (future != null) {
                    future.complete(node);
                }
            }
        } catch (Exception ex) {
            log.debug("Mensaje CDP no JSON: {}", ex.getMessage());
        }
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket,
                                                           int statusCode,
                                                           String reason) {
        closed = true;
        completeAllExceptionally(new HapagLloydTrackingException(
                "CDP cerrado (" + statusCode + "): " + reason));
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed = true;
        completeAllExceptionally(new HapagLloydTrackingException(
                "Error en CDP: " + error.getMessage(), error));
    }

    private void completeAllExceptionally(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = webSocket;
        webSocket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                        .get(3, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.debug("No se pudo cerrar CDP limpiamente: {}", ex.getMessage());
            }
            try {
                current.abort();
            } catch (Exception ex) {
                log.debug("No se pudo abortar CDP: {}", ex.getMessage());
            }
        }
        completeAllExceptionally(new HapagLloydTrackingException("CDP cerrado"));
    }
}
