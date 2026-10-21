package com.shiparrival.adapter.one;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Component
public class OneLineTrackingAdapter implements CarrierTrackingAdapter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient restClient;
    private final String baseUrl;

    @Autowired
    public OneLineTrackingAdapter(RestClient.Builder restClientBuilder,
                                  @Value("${tracking.one.base-url:https://ecomm.one-line.com/api}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = restClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public OneLineTrackingAdapter(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, "https://ecomm.one-line.com/api");
    }

    @Override
    public boolean supports(Carrier carrier) {
        return carrier == Carrier.ONE;
    }

    @Override
    public TrackingResult track(String trackingNumber) {
        String cleanTrackingNumber = trackingNumber != null ? trackingNumber.trim() : "";
        String url = baseUrl + "/cargo-tracking?trackingNo=" + cleanTrackingNumber;

        try {
            OneTrackingResponseDto response = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(OneTrackingResponseDto.class);

            if (response == null || response.getData() == null) {
                return TrackingResult.failure(cleanTrackingNumber, "Respuesta vacía del servicio de ONE");
            }

            OneTrackingResponseDto.OneShipmentData data = response.getData();

            String containerNo = null;
            if (data.getContainers() != null && !data.getContainers().isEmpty()) {
                containerNo = data.getContainers().get(0).getContainerNo();
            }

            LocalDateTime departureDate = parseDateTime(data.getDepartureDate());
            LocalDateTime estimatedArrival = parseDateTime(data.getEstimatedArrival());
            LocalDateTime actualArrival = parseDateTime(data.getActualArrival());

            ShipmentStatus status = resolveStatus(data.getStatus(), actualArrival);

            return TrackingResult.builder()
                    .success(true)
                    .trackingNumber(cleanTrackingNumber)
                    .containerNumber(containerNo)
                    .vesselName(data.getVesselName())
                    .voyageNumber(data.getVoyage())
                    .portOfLoading(data.getPol())
                    .portOfDischarge(data.getPod())
                    .departureDate(departureDate)
                    .estimatedArrival(estimatedArrival)
                    .actualArrival(actualArrival)
                    .detectedStatus(status)
                    .rawStatusMessage(data.getStatus())
                    .build();

        } catch (RestClientResponseException ex) {
            log.error("Error HTTP al consultar ONE para guía {}: Status {}", cleanTrackingNumber, ex.getStatusCode());
            return TrackingResult.failure(cleanTrackingNumber, "Error HTTP " + ex.getStatusCode().value() + ": " + ex.getStatusText());
        } catch (Exception ex) {
            log.error("Excepción inesperada al consultar ONE para guía {}: {}", cleanTrackingNumber, ex.getMessage());
            return TrackingResult.failure(cleanTrackingNumber, "Error de conexión: " + ex.getMessage());
        }
    }

    private ShipmentStatus resolveStatus(String statusStr, LocalDateTime actualArrival) {
        if (actualArrival != null) {
            return ShipmentStatus.ARRIVED;
        }
        if (statusStr == null) {
            return ShipmentStatus.IN_TRANSIT;
        }
        String upper = statusStr.toUpperCase();
        if (upper.contains("ARRIV") || upper.contains("DISCHARG") || upper.contains("DELIVERED")) {
            return ShipmentStatus.ARRIVED;
        }
        return ShipmentStatus.IN_TRANSIT;
    }

    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        String trimmed = dateStr.trim();
        try {
            return LocalDateTime.parse(trimmed, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(trimmed + " 00:00", DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                log.warn("No se pudo parsear fecha '{}' devuelta por ONE", dateStr);
                return null;
            }
        }
    }
}
