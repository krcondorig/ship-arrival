package com.shiparrival.adapter.hapaglloyd;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adaptador de rastreo para Hapag-Lloyd.
 *
 * <p>Hapag-Lloyd no permite consultar su portal con peticiones HTTP directas
 * ni con Chrome headless (Cloudflare bloquea ambas vías). El cliente abre un
 * Chrome normal fuera de pantalla con un perfil persistente y extrae la
 * información de las tablas públicas de Track &amp; Trace. Este adaptador
 * traduce ese snapshot al modelo común de la aplicación.</p>
 */
@Slf4j
@Component
public class HapagLloydTrackingAdapter implements CarrierTrackingAdapter {

    private static final String VESSEL_DEPARTED = "vessel departed";
    private static final String LOADED = "loaded";
    private static final String VESSEL_ARRIVED = "vessel arrived";
    private static final String DISCHARGED = "discharged";

    private static final DateTimeFormatter RAW_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HapagLloydTrackingClient client;

    public HapagLloydTrackingAdapter(HapagLloydTrackingClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(Carrier carrier) {
        return carrier == Carrier.HAPAG_LLOYD;
    }

    @Override
    public TrackingResult track(String trackingNumber) {
        String cleanTrackingNumber = trackingNumber != null ? trackingNumber.trim() : "";
        if (cleanTrackingNumber.isEmpty()) {
            return TrackingResult.failure(cleanTrackingNumber,
                    "El número de guía no puede estar vacío");
        }

        try {
            HapagLloydTrackingSnapshot snapshot = client.fetchTrackingSnapshot(cleanTrackingNumber);
            return mapTrackingResult(cleanTrackingNumber, snapshot);
        } catch (HapagLloydTrackingException ex) {
            log.warn("Error al consultar Hapag-Lloyd para la guía {}: {}",
                    cleanTrackingNumber, ex.getMessage());
            return TrackingResult.failure(cleanTrackingNumber, ex.getMessage());
        } catch (Exception ex) {
            log.error("Error inesperado al procesar la respuesta de Hapag-Lloyd para la guía {}: {}",
                    cleanTrackingNumber, ex.getMessage(), ex);
            return TrackingResult.failure(cleanTrackingNumber,
                    "Error al procesar la respuesta de Hapag-Lloyd: " + ex.getMessage());
        }
    }

    @Override
    public void releaseResources() {
        client.close();
    }

    private TrackingResult mapTrackingResult(String trackingNumber,
                                             HapagLloydTrackingSnapshot snapshot) {
        if (snapshot == null) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd no devolvió datos para la guía " + trackingNumber);
        }
        if (snapshot.getContainers() == null || snapshot.getContainers().isEmpty()) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd no devolvió contenedores para la guía " + trackingNumber);
        }

        HapagLloydContainer container = snapshot.getContainers().get(0);
        if (container == null) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd no devolvió contenedores para la guía " + trackingNumber);
        }
        if (container.getMovements() == null || container.getMovements().isEmpty()) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd no devolvió movimientos para la guía " + trackingNumber);
        }

        List<MovementContext> movements = toContexts(container.getMovements());
        if (movements.isEmpty()) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd no devolvió movimientos para la guía " + trackingNumber);
        }
        if (isCancelled(container, movements)) {
            return TrackingResult.failure(trackingNumber,
                    "Hapag-Lloyd reporta el embarque como cancelado: " + trackingNumber);
        }

        MovementContext departure = findDeparture(movements);
        MovementContext finalPortArrival = findFinalPortArrival(movements);
        LocalDateTime actualArrival = findActualArrival(movements, finalPortArrival);
        LocalDateTime expectedArrival = findExpectedArrival(movements, finalPortArrival);
        MovementContext latestVesselEvent = findLatestVesselEvent(movements);

        LocalDateTime estimatedArrival = actualArrival != null
                ? actualArrival
                : expectedArrival;

        String portOfLoading = departure != null
                ? blankToNull(departure.movement().getPlace())
                : null;
        if (portOfLoading == null) {
            portOfLoading = findFirstVesselPlace(movements);
        }

        String portOfDischarge = finalPortArrival != null
                ? blankToNull(finalPortArrival.movement().getPlace())
                : null;
        if (portOfDischarge == null && latestVesselEvent != null) {
            portOfDischarge = blankToNull(latestVesselEvent.movement().getPlace());
        }

        ShipmentStatus detectedStatus = actualArrival != null
                ? ShipmentStatus.ARRIVED
                : ShipmentStatus.IN_TRANSIT;

        return TrackingResult.builder()
                .success(true)
                .trackingNumber(trackingNumber)
                .containerNumber(normalizeContainerNumber(container.getContainerNumber()))
                .vesselName(latestVesselEvent != null
                        ? blankToNull(latestVesselEvent.movement().getTransport())
                        : null)
                .voyageNumber(latestVesselEvent != null
                        ? blankToNull(latestVesselEvent.movement().getVoyageNumber())
                        : null)
                .portOfLoading(portOfLoading)
                .portOfDischarge(portOfDischarge)
                .departureDate(departure != null ? departure.dateTime() : null)
                .estimatedArrival(estimatedArrival)
                .actualArrival(actualArrival)
                .detectedStatus(detectedStatus)
                .rawStatusMessage(buildRawStatusMessage(findRawStatus(movements)))
                .build();
    }

    private List<MovementContext> toContexts(List<HapagLloydMovement> movements) {
        List<MovementContext> contexts = new ArrayList<>();
        for (int i = 0; i < movements.size(); i++) {
            HapagLloydMovement movement = movements.get(i);
            if (movement == null) {
                continue;
            }
            contexts.add(new MovementContext(movement, i, parseDateTime(
                    movement.getDate(), movement.getTime())));
        }
        return contexts;
    }

    private boolean isCancelled(HapagLloydContainer container, List<MovementContext> movements) {
        if (containsCancel(container.getStatus())) {
            return true;
        }
        return movements.stream()
                .anyMatch(context -> containsCancel(context.movement().getStatus()));
    }

    private boolean containsCancel(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("cancel");
    }

    /**
     * El primer movimiento del buque en el puerto de origen. Se prefiere el
     * "Vessel departed" real y, si no existe, el "Loaded" real.
     */
    private MovementContext findDeparture(List<MovementContext> movements) {
        MovementContext departure = findEarliest(movements, VESSEL_DEPARTED, true);
        if (departure == null) {
            departure = findEarliest(movements, LOADED, true);
        }
        if (departure == null) {
            departure = findEarliest(movements, VESSEL_DEPARTED, false);
        }
        if (departure == null) {
            departure = findEarliest(movements, LOADED, false);
        }
        return departure;
    }

    private MovementContext findEarliest(List<MovementContext> movements,
                                         String status,
                                         boolean actual) {
        return movements.stream()
                .filter(context -> status.equalsIgnoreCase(trim(context.movement().getStatus())))
                .filter(context -> context.movement().isActual() == actual)
                .filter(context -> context.dateTime() != null)
                .filter(this::isVesselMovement)
                .min(Comparator.comparing(MovementContext::dateTime))
                .orElse(null);
    }

    /**
     * El último hito de llegada/descarga del buque. Los arribos intermedios de
     * transbordo quedan antes en la tabla y se descartan.
     */
    private MovementContext findFinalPortArrival(List<MovementContext> movements) {
        return movements.stream()
                .filter(this::isFinalPortMovement)
                .filter(context -> blankToNull(context.movement().getPlace()) != null)
                .max(Comparator.comparingInt(MovementContext::index))
                .orElse(null);
    }

    private LocalDateTime findActualArrival(List<MovementContext> movements,
                                            MovementContext finalPortArrival) {
        if (finalPortArrival == null) {
            return null;
        }
        String place = normalize(finalPortArrival.movement().getPlace());
        return movements.stream()
                .filter(this::isFinalPortMovement)
                .filter(context -> samePlace(context.movement().getPlace(), place))
                .filter(context -> context.movement().isActual())
                .map(MovementContext::dateTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private LocalDateTime findExpectedArrival(List<MovementContext> movements,
                                              MovementContext finalPortArrival) {
        if (finalPortArrival == null) {
            return null;
        }
        String place = normalize(finalPortArrival.movement().getPlace());
        return movements.stream()
                .filter(this::isFinalPortMovement)
                .filter(context -> samePlace(context.movement().getPlace(), place))
                .filter(context -> !context.movement().isActual())
                .map(MovementContext::dateTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Último movimiento asociado a un buque (con número de viaje). Se usa para
     * mostrar el buque y viaje actuales, incluso cuando el arribo es planificado.
     */
    private MovementContext findLatestVesselEvent(List<MovementContext> movements) {
        MovementContext latest = null;
        for (MovementContext context : movements) {
            if (!isVesselMovement(context)) {
                continue;
            }
            if (latest == null) {
                latest = context;
                continue;
            }
            LocalDateTime current = context.dateTime();
            LocalDateTime previous = latest.dateTime();
            if (current != null && (previous == null || current.isAfter(previous))) {
                latest = context;
            } else if (current == null && previous == null
                    && context.index() > latest.index()) {
                latest = context;
            }
        }
        return latest;
    }

    private String findFirstVesselPlace(List<MovementContext> movements) {
        return movements.stream()
                .filter(this::isVesselMovement)
                .filter(context -> blankToNull(context.movement().getPlace()) != null)
                .min(Comparator.comparingInt(MovementContext::index))
                .map(context -> blankToNull(context.movement().getPlace()))
                .orElse(null);
    }

    private MovementContext findRawStatus(List<MovementContext> movements) {
        MovementContext latestActual = movements.stream()
                .filter(context -> context.movement().isActual())
                .filter(context -> context.dateTime() != null)
                .max(Comparator.comparing(MovementContext::dateTime))
                .orElse(null);
        if (latestActual != null) {
            return latestActual;
        }

        MovementContext latestDated = movements.stream()
                .filter(context -> context.dateTime() != null)
                .max(Comparator.comparing(MovementContext::dateTime))
                .orElse(null);
        if (latestDated != null) {
            return latestDated;
        }

        return movements.stream()
                .max(Comparator.comparingInt(MovementContext::index))
                .orElse(null);
    }

    private String buildRawStatusMessage(MovementContext context) {
        if (context == null) {
            return "Sin eventos de Hapag-Lloyd";
        }
        HapagLloydMovement movement = context.movement();
        StringBuilder message = new StringBuilder();
        appendPart(message, blankToNull(movement.getStatus()));
        if (context.dateTime() != null) {
            appendPart(message, context.dateTime().format(RAW_DATE_TIME));
        }
        appendPart(message, blankToNull(movement.getPlace()));
        String transport = blankToNull(movement.getTransport());
        String voyage = blankToNull(movement.getVoyageNumber());
        if (transport != null && voyage != null) {
            appendPart(message, transport + " " + voyage);
        } else if (transport != null) {
            appendPart(message, transport);
        }
        return message.toString();
    }

    private void appendPart(StringBuilder message, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!message.isEmpty()) {
            message.append(" - ");
        }
        message.append(part);
    }

    private boolean isFinalPortMovement(MovementContext context) {
        String status = trim(context.movement().getStatus());
        return VESSEL_ARRIVED.equalsIgnoreCase(status)
                || DISCHARGED.equalsIgnoreCase(status);
    }

    private boolean isVesselMovement(MovementContext context) {
        return blankToNull(context.movement().getVoyageNumber()) != null;
    }

    private boolean samePlace(String first, String normalizedSecond) {
        return normalizedSecond != null && Objects.equals(normalize(first), normalizedSecond);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private LocalDateTime parseDateTime(String date, String time) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(date.trim());
            if (time == null || time.isBlank()) {
                return localDate.atStartOfDay();
            }
            try {
                return LocalDateTime.of(localDate, LocalTime.parse(time.trim()));
            } catch (DateTimeParseException ex) {
                return LocalDateTime.of(localDate,
                        LocalTime.parse(time.trim(), DateTimeFormatter.ofPattern("H:mm")));
            }
        } catch (DateTimeParseException ex) {
            log.debug("Fecha de Hapag-Lloyd no reconocida: {} {}", date, time);
            return null;
        }
    }

    private String normalizeContainerNumber(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.replaceAll("\\s+", "");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MovementContext(HapagLloydMovement movement,
                                   int index,
                                   LocalDateTime dateTime) {
    }
}
