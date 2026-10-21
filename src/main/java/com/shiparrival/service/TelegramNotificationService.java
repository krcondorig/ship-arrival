package com.shiparrival.service;

import com.shiparrival.domain.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Service
public class TelegramNotificationService {

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestClient restClient;
    private final String botToken;
    private final String chatId;
    private final boolean enabled;
    private final String apiBaseUrl;

    @Autowired
    public TelegramNotificationService(RestClient.Builder restClientBuilder,
                                       @Value("${telegram.bot.token:}") String botToken,
                                       @Value("${telegram.bot.chat-id:}") String chatId,
                                       @Value("${telegram.bot.enabled:false}") boolean enabled,
                                       @Value("${telegram.api.base-url:https://api.telegram.org}") String apiBaseUrl) {
        this.botToken = botToken != null ? botToken.trim() : "";
        this.chatId = chatId != null ? chatId.trim() : "";
        this.enabled = enabled;
        this.apiBaseUrl = apiBaseUrl;
        this.restClient = restClientBuilder.build();
    }

    public TelegramNotificationService(RestClient.Builder restClientBuilder,
                                       String botToken,
                                       String chatId,
                                       boolean enabled) {
        this(restClientBuilder, botToken, chatId, enabled, "https://api.telegram.org");
    }

    public boolean sendNearArrivalAlert(Shipment shipment, long daysRemaining) {
        if (!isConfiguredAndEnabled()) {
            log.debug("Notificaciones de Telegram desactivadas o sin configurar");
            return false;
        }

        String etaStr = shipment.getEstimatedArrival() != null
                ? shipment.getEstimatedArrival().format(DISPLAY_FORMATTER)
                : "No disponible";

        String message = String.format("""
                🚢 <b>ALERTA DE ARRIBO PRÓXIMO (≤ 3 DÍAS)</b>
                📦 <b>Guía (B/L):</b> <code>%s</code>
                🏢 <b>Naviera:</b> %s
                ⚓ <b>Buque:</b> %s %s
                📍 <b>Puerto Destino:</b> %s
                📅 <b>ETA Estimado:</b> %s hrs
                ⏳ <b>Días Restantes:</b> %d día(s)
                ℹ️ <b>Estatus:</b> %s
                """,
                shipment.getTrackingNumber(),
                shipment.getCarrier(),
                shipment.getVesselName() != null ? shipment.getVesselName() : "Por asignar",
                shipment.getVoyageNumber() != null ? "(" + shipment.getVoyageNumber() + ")" : "",
                shipment.getPortOfDischarge() != null ? shipment.getPortOfDischarge() : "No especificado",
                etaStr,
                daysRemaining,
                shipment.getStatus()
        );

        return sendTextMessage(message);
    }

    public boolean sendArrivedAlert(Shipment shipment) {
        if (!isConfiguredAndEnabled()) {
            return false;
        }

        String arrivalStr = shipment.getActualArrival() != null
                ? shipment.getActualArrival().format(DISPLAY_FORMATTER)
                : (shipment.getEstimatedArrival() != null ? shipment.getEstimatedArrival().format(DISPLAY_FORMATTER) : "Confirmado");

        String message = String.format("""
                ✅ <b>BUQUE ARRIBADO A PUERTO</b>
                📦 <b>Guía (B/L):</b> <code>%s</code>
                🏢 <b>Naviera:</b> %s
                ⚓ <b>Buque:</b> %s
                📍 <b>Puerto:</b> %s
                🏁 <b>Fecha Arribo:</b> %s
                """,
                shipment.getTrackingNumber(),
                shipment.getCarrier(),
                shipment.getVesselName() != null ? shipment.getVesselName() : "Confirmado",
                shipment.getPortOfDischarge() != null ? shipment.getPortOfDischarge() : "Puerto destino",
                arrivalStr
        );

        return sendTextMessage(message);
    }

    public boolean sendCustomMessage(String message) {
        if (!isConfiguredAndEnabled()) {
            return false;
        }
        return sendTextMessage(message);
    }

    private boolean sendTextMessage(String htmlText) {
        String endpoint = apiBaseUrl + "/bot" + botToken + "/sendMessage";

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "text", htmlText,
                "parse_mode", "HTML"
        );

        try {
            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Notificación Telegram enviada exitosamente al chat {}", chatId);
            return true;
        } catch (Exception ex) {
            log.warn("Fallo al enviar notificación por Telegram: {}", ex.getMessage());
            return false;
        }
    }

    private boolean isConfiguredAndEnabled() {
        return enabled && !botToken.isEmpty() && !chatId.isEmpty();
    }
}
