package com.shiparrival.adapter.msc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.adapter.msc.dto.MscBillOfLading;
import com.shiparrival.adapter.msc.dto.MscContainerInfo;
import com.shiparrival.adapter.msc.dto.MscEvent;
import com.shiparrival.adapter.msc.dto.MscGeneralTrackingInfo;
import com.shiparrival.adapter.msc.dto.MscTrackingData;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adaptador de rastreo para MSC (Mediterranean Shipping Company).
 *
 * <p>MSC expone la información de seguimiento mediante la API interna
 * {@code /api/feature/tools/TrackingInfo}, protegida por Akamai. El cliente
 * inyectado se encarga de obtener el JSON y este adaptador lo traduce al
 * modelo común {@link TrackingResult}.</p>
 */
@Slf4j
@Component
public class MscTrackingAdapter implements CarrierTrackingAdapter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MscTrackingClient client;
    private final ObjectMapper objectMapper;

    public MscTrackingAdapter(MscTrackingClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(Carrier carrier) {
        return carrier == Carrier.MSC;
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
                        "MSC no devolvió información para la guía " + cleanTrackingNumber);
            }
            if (json.trim().startsWith("<")) {
                return TrackingResult.failure(cleanTrackingNumber,
                        "MSC devolvió una página de bloqueo en lugar de datos para la guía "
                                + cleanTrackingNumber);
            }

            JsonNode root = objectMapper.readTree(json);
            boolean success = root.path("IsSuccess").asBoolean(false);
            if (!success) {
                String message = root.path("Data").isTextual()
                        ? root.path("Data").asText()
                        : "MSC no encontró información para la guía " + cleanTrackingNumber;
                return TrackingResult.failure(cleanTrackingNumber, message);
            }

            JsonNode dataNode = root.path("Data");
            if (dataNode.isMissingNode() || dataNode.isNull() || !dataNode.isObject()) {
                return TrackingResult.failure(cleanTrackingNumber,
                        "MSC no devolvió datos para la guía " + cleanTrackingNumber);
            }

            MscTrackingData data = objectMapper.treeToValue(dataNode, MscTrackingData.class);
            return mapTrackingResult(cleanTrackingNumber, data);
        } catch (MscTrackingException ex) {
            log.warn("Error al consultar MSC para la guía {}: {}", cleanTrackingNumber, ex.getMessage());
            return TrackingResult.failure(cleanTrackingNumber, ex.getMessage());
        } catch (Exception ex) {
            log.error("Error inesperado al procesar la respuesta de MSC para la guía {}: {}",
                    cleanTrackingNumber, ex.getMessage(), ex);
            return TrackingResult.failure(cleanTrackingNumber,
                    "Error al procesar la respuesta de MSC: " + ex.getMessage());
        }
    }

    @Override
    public void releaseResources() {
        client.close();
    }

    private TrackingResult mapTrackingResult(String trackingNumber, MscTrackingData data) {
        if (data.getBillOfLadings() == null || data.getBillOfLadings().isEmpty()) {
            return TrackingResult.failure(trackingNumber,
                    "MSC no devolvió Bill of Ladings para la guía " + trackingNumber);
        }

        MscBillOfLading billOfLading = selectBillOfLading(data, trackingNumber);
        MscGeneralTrackingInfo general = billOfLading.getGeneralTrackingInfo() != null
                ? billOfLading.getGeneralTrackingInfo()
                : new MscGeneralTrackingInfo();
        MscContainerInfo container = firstContainer(billOfLading);
        List<MscEvent> events = container != null && container.getEvents() != null
                ? container.getEvents()
                : List.of();

        String portOfLoading = firstNonBlank(general.getPortOfLoad(), general.getShippedFrom());
        String portOfDischarge = firstNonBlank(general.getPortOfDischarge(), general.getShippedTo());

        MscEvent arrivalEvent = findActualArrivalEvent(events, portOfDischarge);
        MscEvent departureEvent = findDepartureEvent(events);
        MscEvent latestVesselEvent = findLatestVesselEvent(events);
        MscEvent latestEvent = findLatestEvent(events);

        LocalDateTime actualArrival = arrivalEvent != null ? parseDate(arrivalEvent.getDate()) : null;
        LocalDateTime departureDate = departureEvent != null ? parseDate(departureEvent.getDate()) : null;
        LocalDateTime estimatedArrival = firstNonNull(
                parseDate(general.getFinalPodEtaDate()),
                container != null ? parseDate(container.getPodEtaDate()) : null,
                findEtaEventDate(events)
        );

        // Para embarques ya arribados MSC no siempre publica un ETA final.
        // Se usa la fecha real de arribo como referencia de visualización.
        if (estimatedArrival == null && actualArrival != null) {
            estimatedArrival = actualArrival;
        }

        String vesselName = null;
        String voyageNumber = null;
        if (latestVesselEvent != null && latestVesselEvent.getDetail() != null) {
            List<String> detail = latestVesselEvent.getDetail();
            if (!detail.isEmpty()) {
                vesselName = blankToNull(detail.get(0));
            }
            if (detail.size() > 1) {
                voyageNumber = blankToNull(detail.get(1));
            }
        }

        ShipmentStatus detectedStatus = actualArrival != null
                ? ShipmentStatus.ARRIVED
                : ShipmentStatus.IN_TRANSIT;

        return TrackingResult.builder()
                .success(true)
                .trackingNumber(firstNonBlank(data.getTrackingNumber(), trackingNumber))
                .containerNumber(container != null ? blankToNull(container.getContainerNumber()) : null)
                .vesselName(vesselName)
                .voyageNumber(voyageNumber)
                .portOfLoading(blankToNull(portOfLoading))
                .portOfDischarge(blankToNull(portOfDischarge))
                .departureDate(departureDate)
                .estimatedArrival(estimatedArrival)
                .actualArrival(actualArrival)
                .detectedStatus(detectedStatus)
                .rawStatusMessage(buildRawStatusMessage(latestEvent))
                .build();
    }

    private MscBillOfLading selectBillOfLading(MscTrackingData data, String trackingNumber) {
        return data.getBillOfLadings().stream()
                .filter(bl -> bl.getBillOfLadingNumber() != null
                        && bl.getBillOfLadingNumber().equalsIgnoreCase(trackingNumber))
                .findFirst()
                .orElse(data.getBillOfLadings().get(0));
    }

    private MscContainerInfo firstContainer(MscBillOfLading billOfLading) {
        return billOfLading.getContainersInfo() != null && !billOfLading.getContainersInfo().isEmpty()
                ? billOfLading.getContainersInfo().get(0)
                : null;
    }

    private MscEvent findLatestEvent(List<MscEvent> events) {
        return events.stream()
                .max(Comparator.comparingInt(this::eventOrder))
                .orElse(null);
    }

    private MscEvent findDepartureEvent(List<MscEvent> events) {
        return events.stream()
                .filter(event -> "Export Loaded on Vessel".equalsIgnoreCase(event.getDescription()))
                .min(Comparator.comparingInt(this::eventOrder))
                .orElse(null);
    }

    private MscEvent findActualArrivalEvent(List<MscEvent> events, String portOfDischarge) {
        return events.stream()
                .filter(event -> isAtDestination(event, portOfDischarge))
                .filter(this::isActualArrival)
                .min(Comparator.comparing(event -> {
                    LocalDateTime date = parseDate(event.getDate());
                    return date != null ? date : LocalDateTime.MAX;
                }))
                .orElse(null);
    }

    private MscEvent findLatestVesselEvent(List<MscEvent> events) {
        return events.stream()
                .filter(this::hasVesselDetails)
                .max(Comparator.comparingInt(this::eventOrder))
                .orElse(null);
    }

    private LocalDateTime findEtaEventDate(List<MscEvent> events) {
        return events.stream()
                .filter(this::isEtaEvent)
                .max(Comparator.comparingInt(this::eventOrder))
                .map(event -> parseDate(event.getDate()))
                .orElse(null);
    }

    private int eventOrder(MscEvent event) {
        return event.getOrder() != null ? event.getOrder() : -1;
    }

    private boolean hasVesselDetails(MscEvent event) {
        if (event.getDetail() == null || event.getDetail().size() < 2) {
            return false;
        }
        if (event.getVessel() != null
                && event.getVessel().getImo() != null
                && !event.getVessel().getImo().isBlank()) {
            return true;
        }
        String description = event.getDescription() != null
                ? event.getDescription().toLowerCase(Locale.ROOT)
                : "";
        return description.contains("vessel")
                || description.contains("arrival")
                || description.contains("discharged");
    }

    private boolean isEtaEvent(MscEvent event) {
        if (event.getDescription() == null) {
            return false;
        }
        String description = event.getDescription().toLowerCase(Locale.ROOT);
        return description.contains("estimated time of arrival") || description.equals("eta");
    }

    private boolean isActualArrival(MscEvent event) {
        if (event.getDescription() == null) {
            return false;
        }
        String description = event.getDescription().toLowerCase(Locale.ROOT);
        if (description.contains("estimated")
                || description.contains("transshipment")
                || description.contains("eta")
                || description.contains("etd")) {
            return false;
        }
        return description.contains("discharged")
                || description.contains("vessel arrival")
                || description.contains("arrival at port")
                || description.contains("import arrival");
    }

    private boolean isAtDestination(MscEvent event, String portOfDischarge) {
        if (portOfDischarge == null || portOfDischarge.isBlank()) {
            return true;
        }
        String location = normalizeLocation(event.getLocation());
        String destination = normalizeLocation(portOfDischarge);
        if (location == null || destination == null) {
            return true;
        }
        return location.equals(destination)
                || location.startsWith(destination)
                || destination.startsWith(location)
                || location.contains(destination)
                || destination.contains(location);
    }

    private String normalizeLocation(String location) {
        if (location == null) {
            return null;
        }
        String normalized = location.trim().toLowerCase(Locale.ROOT);
        int comma = normalized.indexOf(',');
        if (comma > 0) {
            normalized = normalized.substring(0, comma).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDateTime.parse(trimmed, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // La mayoría de los eventos de MSC solo incluyen fecha.
        }
        try {
            return LocalDate.parse(trimmed, DATE_FORMATTER).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            log.debug("Fecha de MSC no reconocida: {}", value);
            return null;
        }
    }

    private String buildRawStatusMessage(MscEvent event) {
        if (event == null) {
            return "Sin eventos de MSC";
        }
        StringBuilder message = new StringBuilder();
        if (event.getDescription() != null) {
            message.append(event.getDescription());
        }
        if (event.getDate() != null) {
            appendSeparator(message);
            message.append(event.getDate());
        }
        if (event.getLocation() != null) {
            appendSeparator(message);
            message.append(event.getLocation());
        }
        return message.toString();
    }

    private void appendSeparator(StringBuilder message) {
        if (!message.isEmpty()) {
            message.append(" - ");
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

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        return java.util.Arrays.stream(values)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
