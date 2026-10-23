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
public class DiscordNotificationService {

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestClient restClient;
    private final String webhookUrl;
    private final boolean enabled;

    @Autowired
    public DiscordNotificationService(RestClient.Builder restClientBuilder,
                                      @Value("${discord.webhook.url:}") String webhookUrl,
                                      @Value("${discord.bot.enabled:false}") boolean enabled) {
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : "";
        this.enabled = enabled;
        this.restClient = restClientBuilder.build();
        log.info("DiscordNotificationService inicializado - Habilitado: {}, Webhook configurado: {}",
                this.enabled, !this.webhookUrl.isEmpty());
    }

    public boolean isConfiguredAndEnabled() {
        return enabled && !webhookUrl.isEmpty();
    }

    public boolean sendNearArrivalAlert(Shipment shipment, long daysRemaining) {
        if (!isConfiguredAndEnabled()) {
            log.debug("Notificaciones de Discord desactivadas o sin configurar");
            return false;
        }

        String etaStr = shipment.getEstimatedArrival() != null
                ? shipment.getEstimatedArrival().format(DISPLAY_FORMATTER)
                : "No disponible";

        String message = String.format("""
                🚨 **ALERTA DE ARRIBO PRÓXIMO (≤ 3 DÍAS)** 🚨
                > **Guía (B/L):** `%s`
                > **Naviera:** %s
                > **Buque:** %s %s
                > **Puerto Destino:** %s
                > **ETA Estimado:** %s hrs
                > **Días Restantes:** %d día(s)
                > **Estatus:** %s
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

        return sendWebhookMessage(message);
    }

    public boolean sendArrivedAlert(Shipment shipment) {
        if (!isConfiguredAndEnabled()) {
            return false;
        }

        String arrivalStr = shipment.getActualArrival() != null
                ? shipment.getActualArrival().format(DISPLAY_FORMATTER)
                : (shipment.getEstimatedArrival() != null ? shipment.getEstimatedArrival().format(DISPLAY_FORMATTER) : "Confirmado");

        String message = String.format("""
                ✅ **BUQUE ARRIBADO A PUERTO** ✅
                > **Guía (B/L):** `%s`
                > **Naviera:** %s
                > **Buque:** %s
                > **Puerto:** %s
                > **Fecha Arribo:** %s
                """,
                shipment.getTrackingNumber(),
                shipment.getCarrier(),
                shipment.getVesselName() != null ? shipment.getVesselName() : "Confirmado",
                shipment.getPortOfDischarge() != null ? shipment.getPortOfDischarge() : "Puerto destino",
                arrivalStr
        );

        return sendWebhookMessage(message);
    }

    private boolean sendWebhookMessage(String content) {
        try {
            Map<String, String> payload = Map.of("content", content);

            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Notificación Discord enviada exitosamente");
            return true;
        } catch (Exception ex) {
            log.error("Fallo al enviar notificación por Discord: {}", ex.getMessage());
            return false;
        }
    }
}
