package com.shiparrival.adapter.maersk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.adapter.maersk.dto.MaerskContainer;
import com.shiparrival.adapter.maersk.dto.MaerskContainerLocation;
import com.shiparrival.adapter.maersk.dto.MaerskEvent;
import com.shiparrival.adapter.maersk.dto.MaerskLocationInfo;
import com.shiparrival.adapter.maersk.dto.MaerskTrackingResponse;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adaptador de rastreo para Maersk.
 *
 * <p>Traduce la respuesta de la API interna del portal de Maersk al modelo
 * común {@link TrackingResult}. El puerto de descarga se obtiene del evento
 * {@code CONTAINER ARRIVAL}/{@code DISCHARG} del puerto final, no del destino
 * terrestre de la guía (por ejemplo, Manzanillo en lugar de Ciudad de México).</p>
 */
@Slf4j
@Component
public class MaerskTrackingAdapter implements CarrierTrackingAdapter {

    private static final String CONTAINER_ARRIVAL = "CONTAINER ARRIVAL";
    private static final String DISCHARG = "DISCHARG";
    private static final String CONTAINER_DEPARTURE = "CONTAINER DEPARTURE";
    private static final String LOAD = "LOAD";

    private final MaerskTrackingClient client;
    private final ObjectMapper objectMapper;

    public MaerskTrackingAdapter(MaerskTrackingClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(Carrier carrier) {
        return carrier == Carrier.MAERSK;
    }

    @Override
    public TrackingResult track(String trackingNumber) {
        String cleanTrackingNumber = trackingNumber != null ? trackingNumber.trim() : "";
        if (cleanTrackingNumber.isEmpty()) {
            return TrackingResult.failure(cleanTrackingNumber, "El número de guía no puede estar vacío");
        }

        try {
            String json = client.fetchTrackingJson(cleanTrackingNumber);
            if (json == null || json.isBlank()) {
                return TrackingResult.failure(cleanTrackingNumber,
                        "Maersk no devolvió información para la guía " + cleanTrackingNumber);
            }
            if (json.trim().startsWith("<") || json.contains("Access Denied")) {
                return TrackingResult.failure(cleanTrackingNumber,
                        "Maersk devolvió una página de bloqueo en lugar de datos para la guía "
                                + cleanTrackingNumber);
            }

            MaerskTrackingResponse response = objectMapper.readValue(json, MaerskTrackingResponse.class);
            return mapTrackingResult(cleanTrackingNumber, response);
        } catch (MaerskTrackingException ex) {
            log.warn("Error al consultar Maersk para la guía {}: {}", cleanTrackingNumber, ex.getMessage());
            return TrackingResult.failure(cleanTrackingNumber, ex.getMessage());
        } catch (Exception ex) {
            log.error("Error inesperado al procesar la respuesta de Maersk para la guía {}: {}",
                    cleanTrackingNumber, ex.getMessage(), ex);
            return TrackingResult.failure(cleanTrackingNumber,
                    "Error al procesar la respuesta de Maersk: " + ex.getMessage());
        }
    }

    @Override
    public void releaseResources() {
        client.close();
    }

    private TrackingResult mapTrackingResult(String trackingNumber, MaerskTrackingResponse response) {
        if (response == null) {
            return TrackingResult.failure(trackingNumber,
                    "Maersk no devolvió datos para la guía " + trackingNumber);
        }
        if (response.isCancelledShipment()) {
            return TrackingResult.failure(trackingNumber,
                    "Maersk reporta el embarque como cancelado: " + trackingNumber);
        }
        if (response.getContainers() == null || response.getContainers().isEmpty()) {
            return TrackingResult.failure(trackingNumber,
                    "Maersk no devolvió contenedores para la guía " + trackingNumber);
        }

        MaerskContainer container = response.getContainers().get(0);
        List<EventContext> events = flattenEvents(container);

        EventContext departure = findEarliestActual(events, CONTAINER_DEPARTURE);
        if (departure == null) {
            departure = findEarliestActual(events, LOAD);
        }

        EventContext finalPortArrival = findLatestPortArrival(events);
        MaerskContainerLocation podLocation = finalPortArrival != null
                ? finalPortArrival.location()
                : null;

        LocalDateTime departureDate = departure != null
                ? parseDateTime(departure.event().getEventTime())
                : null;
        LocalDateTime actualArrival = findActualArrival(events, podLocation);
        LocalDateTime expectedArrival = findExpectedArrival(events, podLocation);

        LocalDateTime estimatedArrival = expectedArrival;
        if (estimatedArrival == null && actualArrival != null) {
            estimatedArrival = actualArrival;
        }
        if (estimatedArrival == null) {
            LocalDateTime finalDeliveryEta = parseDateTime(container.getEtaFinalDelivery());
            if (finalDeliveryEta != null) {
                log.debug("Maersk no publicó ETA en el puerto de descarga para la guía {}; "
                        + "se usa eta_final_delivery como referencia", trackingNumber);
                estimatedArrival = finalDeliveryEta;
            }
        }

        EventContext latestVesselEvent = findLatestVesselEvent(events);
        EventContext rawStatusEvent = findRawStatusEvent(events);

        String portOfLoading = departure != null
                ? formatLocation(departure.location())
                : formatLocation(response.getOrigin());

        String portOfDischarge = formatLocation(podLocation);
        if (portOfDischarge == null) {
            portOfDischarge = formatLocation(response.getDestination());
        }

        ShipmentStatus detectedStatus = actualArrival != null
                ? ShipmentStatus.ARRIVED
                : ShipmentStatus.IN_TRANSIT;

        return TrackingResult.builder()
                .success(true)
                .trackingNumber(firstNonBlank(response.getTpdocNum(), trackingNumber))
                .containerNumber(blankToNull(container.getContainerNum()))
                .vesselName(latestVesselEvent != null
                        ? blankToNull(latestVesselEvent.event().getVesselName())
                        : null)
                .voyageNumber(latestVesselEvent != null
                        ? blankToNull(latestVesselEvent.event().getVoyageNum())
                        : null)
                .portOfLoading(portOfLoading)
                .portOfDischarge(portOfDischarge)
                .departureDate(departureDate)
                .estimatedArrival(estimatedArrival)
                .actualArrival(actualArrival)
                .detectedStatus(detectedStatus)
                .rawStatusMessage(buildRawStatusMessage(rawStatusEvent))
                .build();
    }

    private List<EventContext> flattenEvents(MaerskContainer container) {
        List<EventContext> events = new ArrayList<>();
        if (container == null || container.getLocations() == null) {
            return events;
        }
        for (MaerskContainerLocation location : container.getLocations()) {
            if (location == null || location.getEvents() == null) {
                continue;
            }
            for (MaerskEvent event : location.getEvents()) {
                if (event != null) {
                    events.add(new EventContext(event, location));
                }
            }
        }
        return events;
    }

    private EventContext findEarliestActual(List<EventContext> events, String activity) {
        return events.stream()
                .filter(context -> isActivity(context, activity))
                .filter(this::isActual)
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .min(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
    }

    /**
     * El arribo al puerto final es el último evento de llegada/descarga. Los
     * arribos intermedios por transbordo ocurren antes y quedan descartados.
     */
    private EventContext findLatestPortArrival(List<EventContext> events) {
        return events.stream()
                .filter(context -> isActivity(context, CONTAINER_ARRIVAL)
                        || isActivity(context, DISCHARG))
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .max(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
    }

    private LocalDateTime findActualArrival(List<EventContext> events,
                                            MaerskContainerLocation podLocation) {
        if (podLocation == null) {
            return null;
        }
        return events.stream()
                .filter(context -> sameLocation(context.location(), podLocation))
                .filter(context -> isActivity(context, CONTAINER_ARRIVAL)
                        || isActivity(context, DISCHARG))
                .filter(this::isActual)
                .map(context -> parseDateTime(context.event().getEventTime()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private LocalDateTime findExpectedArrival(List<EventContext> events,
                                              MaerskContainerLocation podLocation) {
        if (podLocation == null) {
            return null;
        }
        return events.stream()
                .filter(context -> sameLocation(context.location(), podLocation))
                .filter(context -> isActivity(context, CONTAINER_ARRIVAL)
                        || isActivity(context, DISCHARG))
                .filter(this::isExpected)
                .map(context -> parseDateTime(context.event().getEventTime()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private EventContext findLatestVesselEvent(List<EventContext> events) {
        return events.stream()
                .filter(context -> blankToNull(context.event().getVesselName()) != null)
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .max(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
    }

    /**
     * Para el mensaje de estado se prefieren los hitos esperados (el siguiente
     * hito del viaje); si no hay, se usa el último evento real.
     */
    private EventContext findRawStatusEvent(List<EventContext> events) {
        EventContext expected = events.stream()
                .filter(this::isExpected)
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .min(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
        if (expected != null) {
            return expected;
        }

        EventContext actual = events.stream()
                .filter(this::isActual)
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .max(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
        if (actual != null) {
            return actual;
        }

        return events.stream()
                .filter(context -> parseDateTime(context.event().getEventTime()) != null)
                .max(Comparator.comparing(context -> parseDateTime(context.event().getEventTime())))
                .orElse(null);
    }

    private boolean isActivity(EventContext context, String activity) {
        String eventActivity = context.event().getActivity();
        return eventActivity != null && activity.equalsIgnoreCase(eventActivity.trim());
    }

    private boolean isActual(EventContext context) {
        String type = context.event().getEventTimeType();
        return type != null && "ACTUAL".equalsIgnoreCase(type.trim());
    }

    private boolean isExpected(EventContext context) {
        String type = context.event().getEventTimeType();
        return type != null && "EXPECTED".equalsIgnoreCase(type.trim());
    }

    private boolean sameLocation(MaerskContainerLocation first, MaerskContainerLocation second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return Objects.equals(normalize(first.getCity()), normalize(second.getCity()))
                && Objects.equals(normalize(first.getCountryCode()), normalize(second.getCountryCode()));
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String formatLocation(MaerskContainerLocation location) {
        if (location == null) {
            return null;
        }
        String city = blankToNull(location.getCity());
        String countryCode = blankToNull(location.getCountryCode());
        if (city != null && countryCode != null) {
            return city + ", " + countryCode;
        }
        return firstNonBlank(city, countryCode);
    }

    private String formatLocation(MaerskLocationInfo location) {
        if (location == null) {
            return null;
        }
        String city = blankToNull(location.getCity());
        String countryCode = blankToNull(location.getCountryCode());
        if (city != null && countryCode != null) {
            return city + ", " + countryCode;
        }
        return firstNonBlank(city, countryCode);
    }

    private String buildRawStatusMessage(EventContext context) {
        if (context == null) {
            return "Sin eventos de Maersk";
        }
        StringBuilder message = new StringBuilder();
        if (context.event().getActivity() != null) {
            message.append(context.event().getActivity());
        }
        if (context.event().getEventTime() != null) {
            appendSeparator(message);
            message.append(context.event().getEventTime());
        }
        String location = formatLocation(context.location());
        if (location != null) {
            appendSeparator(message);
            message.append(location);
        }
        return message.toString();
    }

    private void appendSeparator(StringBuilder message) {
        if (!message.isEmpty()) {
            message.append(" - ");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        // Maersk devuelve la hora local del puerto; se quita una posible Z
        // para no desplazarla a UTC.
        if (trimmed.endsWith("Z")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            log.debug("Fecha de Maersk no reconocida: {}", value);
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record EventContext(MaerskEvent event, MaerskContainerLocation location) {
    }
}
