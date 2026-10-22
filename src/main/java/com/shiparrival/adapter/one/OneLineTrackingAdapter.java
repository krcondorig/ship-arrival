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
import java.util.Map;

@Slf4j
@Component
public class OneLineTrackingAdapter implements CarrierTrackingAdapter {

    private static final String MATRIX_DEPARTURE = "E061";
    private static final String MATRIX_PORT_OF_DISCHARGE_ARRIVAL = "E089";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        if (cleanTrackingNumber.isEmpty()) {
            return TrackingResult.failure(cleanTrackingNumber, "El número de guía no puede estar vacío");
        }

        String url = baseUrl + "/v2/edh/containers/track-and-trace/search";

        Map<String, Object> requestBody = Map.of(
                "page", 1,
                "page_length", 10,
                "filters", Map.of(
                        "search_text", cleanTrackingNumber,
                        "search_type", "ALL"
                ),
                "timestamp", System.currentTimeMillis()
        );

        try {
            OneTrackingResponseDto response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(OneTrackingResponseDto.class);

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                log.info("ONE no devolvió resultados para la guía {}", cleanTrackingNumber);
                return TrackingResult.failure(cleanTrackingNumber,
                        "ONE no devolvió resultados para la guía " + cleanTrackingNumber);
            }

            // Todos los contenedores de una misma B/L comparten buque, viaje y eventos de arribo.
            // Se toma el primero como contenedor representativo del embarque.
            return mapToTrackingResult(cleanTrackingNumber, response.getData().get(0));

        } catch (RestClientResponseException ex) {
            log.warn("Error HTTP al consultar ONE API v2 para guía {}: Status {}", cleanTrackingNumber, ex.getStatusCode());
            return TrackingResult.failure(cleanTrackingNumber,
                    "Error HTTP " + ex.getStatusCode().value() + " al consultar ONE");
        } catch (Exception ex) {
            log.warn("Excepción al consultar ONE API v2 para guía {}: {}", cleanTrackingNumber, ex.getMessage());
            return TrackingResult.failure(cleanTrackingNumber,
                    "Error de conexión con ONE: " + ex.getMessage());
        }
    }

    private TrackingResult mapToTrackingResult(String trackingNumber,
                                               OneTrackingResponseDto.OneContainerData data) {
        OneTrackingResponseDto.OneCargoEvent departureEvent =
                findEvent(data.getCargoEvents(), MATRIX_DEPARTURE);
        OneTrackingResponseDto.OneCargoEvent portArrivalEvent =
                findEvent(data.getCargoEvents(), MATRIX_PORT_OF_DISCHARGE_ARRIVAL);

        LocalDateTime departureDate = parseDateTime(
                departureEvent != null ? departureEvent.getLocalPortDate() : null);

        LocalDateTime portArrivalDate = parseDateTime(
                portArrivalEvent != null ? portArrivalEvent.getLocalPortDate() : null);

        LocalDateTime actualArrival = isActual(portArrivalEvent) ? portArrivalDate : null;
        LocalDateTime estimatedArrival = portArrivalDate;

        // Si ONE aún no marca el evento E089 como ACTUAL pero el último evento ya es
        // un arribo/descarga en el puerto, se usa ese evento como fecha real.
        if (actualArrival == null && isArrivalEvent(data.getLatestEvent())) {
            actualArrival = parseDateTime(data.getLatestEvent().getDate());
            if (estimatedArrival == null) {
                estimatedArrival = actualArrival;
            }
        }

        ShipmentStatus detectedStatus = actualArrival != null
                ? ShipmentStatus.ARRIVED
                : ShipmentStatus.IN_TRANSIT;

        String portOfLoading = departureEvent != null && departureEvent.getLocationName() != null
                ? departureEvent.getLocationName()
                : locationName(data.getPor());

        String portOfDischarge = portArrivalEvent != null && portArrivalEvent.getLocationName() != null
                ? portArrivalEvent.getLocationName()
                : resolvePortOfDischargeFallback(data);

        return TrackingResult.builder()
                .success(true)
                .trackingNumber(data.getBookingNo() != null ? data.getBookingNo() : trackingNumber)
                .containerNumber(data.getContainerNo())
                .vesselName(data.getVesselVoyage() != null ? data.getVesselVoyage().getVesselName() : null)
                .voyageNumber(data.getVesselVoyage() != null ? data.getVesselVoyage().getVoyageNo() : null)
                .portOfLoading(portOfLoading)
                .portOfDischarge(portOfDischarge)
                .departureDate(departureDate)
                .estimatedArrival(estimatedArrival)
                .actualArrival(actualArrival)
                .detectedStatus(detectedStatus)
                .rawStatusMessage(buildRawStatusMessage(data.getLatestEvent()))
                .build();
    }

    private OneTrackingResponseDto.OneCargoEvent findEvent(
            List<OneTrackingResponseDto.OneCargoEvent> events, String matrixId) {
        if (events == null) {
            return null;
        }
        return events.stream()
                .filter(event -> matrixId.equalsIgnoreCase(event.getMatrixId()))
                .findFirst()
                .orElse(null);
    }

    private boolean isActual(OneTrackingResponseDto.OneCargoEvent event) {
        return event != null && "ACTUAL".equalsIgnoreCase(event.getTrigger());
    }

    private boolean isArrivalEvent(OneTrackingResponseDto.OneEvent event) {
        if (event == null || event.getEventName() == null) {
            return false;
        }
        String name = event.getEventName().toLowerCase();
        return name.contains("vessel arrival")
                || name.contains("unloaded from vessel")
                || name.contains("arrival at port");
    }

    private String resolvePortOfDischargeFallback(OneTrackingResponseDto.OneContainerData data) {
        if (data.getPlace() != null && data.getPlace().getLocationName() != null) {
            return data.getPlace().getLocationName();
        }
        return locationName(data.getPod());
    }

    private String locationName(OneTrackingResponseDto.OneLocation location) {
        return location != null ? location.getLocationName() : null;
    }

    private String buildRawStatusMessage(OneTrackingResponseDto.OneEvent event) {
        if (event == null || event.getEventName() == null) {
            return "En tránsito";
        }
        String location = event.getLocationName() != null ? " - " + event.getLocationName() : "";
        return event.getEventName() + location;
    }

    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        String trimmed = dateStr.trim();
        try {
            // ONE devuelve "localPortDate" con sufijo Z, pero representa la hora local
            // del puerto. Se quita la Z para no desplazarla a UTC.
            String withoutZ = trimmed.endsWith("Z")
                    ? trimmed.substring(0, trimmed.length() - 1)
                    : trimmed;
            return LocalDateTime.parse(withoutZ, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(trimmed, DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                log.warn("No se pudo parsear fecha '{}' devuelta por ONE", dateStr);
                return null;
            }
        }
    }
}
