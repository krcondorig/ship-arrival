package com.shiparrival.web;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.service.ShipmentService;
import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.BatchImportSummary;
import com.shiparrival.service.dto.ShipmentDto;
import com.shiparrival.service.dto.ShipmentStats;
import com.shiparrival.service.dto.TrackingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Controller
@RequestMapping
@RequiredArgsConstructor
public class ShipmentWebController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 20, 50);

    private final ShipmentService shipmentService;
    private final TrackingSchedulerService trackingSchedulerService;

    @GetMapping({"/", "/shipments"})
    public String dashboard(@RequestParam(required = false) String search,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "10") int size,
                            Model model) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));

        Page<Shipment> pageResult = shipmentService.getVisibleShipments(search, pageable);

        // Si la página solicitada quedó fuera de rango (por ejemplo, tras eliminar
        // el último registro de la última página), redirigimos a la última válida.
        if (pageResult.getTotalPages() > 0 && safePage >= pageResult.getTotalPages()) {
            return redirectToDashboard(pageResult.getTotalPages() - 1, safeSize, search);
        }

        LocalDate today = LocalDate.now();
        List<ShipmentDto> dtos = pageResult.getContent().stream().map(s -> ShipmentDto.builder()
                .id(s.getId())
                .trackingNumber(s.getTrackingNumber())
                .carrier(s.getCarrier())
                .containerNumber(s.getContainerNumber())
                .vesselName(s.getVesselName())
                .voyageNumber(s.getVoyageNumber())
                .portOfLoading(s.getPortOfLoading())
                .portOfDischarge(s.getPortOfDischarge())
                .departureDate(s.getDepartureDate())
                .estimatedArrival(s.getEstimatedArrival())
                .actualArrival(s.getActualArrival())
                .status(s.getStatus())
                .lastCheckedAt(s.getLastCheckedAt())
                .active(s.isActive())
                .daysRemaining(shipmentService.calculateDaysRemaining(s, today))
                .build()
        ).toList();

        ShipmentStats stats = shipmentService.getVisibleShipmentStats(search);

        int windowStart = Math.max(0, pageResult.getNumber() - 2);
        int windowEnd = Math.min(pageResult.getTotalPages() - 1, pageResult.getNumber() + 2);
        List<Integer> pageNumbers = pageResult.getTotalPages() == 0
                ? List.of()
                : IntStream.rangeClosed(windowStart, windowEnd).boxed().toList();

        model.addAttribute("shipments", dtos);
        model.addAttribute("totalCount", stats.total());
        model.addAttribute("inTransitCount", stats.inTransit());
        model.addAttribute("nearArrivalCount", stats.nearArrival());
        model.addAttribute("arrivedCount", stats.arrived());
        model.addAttribute("carriers", Carrier.values());
        model.addAttribute("isTrackingInProgress", trackingSchedulerService.isTrackingInProgress());

        model.addAttribute("searchQuery", search != null && !search.isBlank() ? search.trim() : null);
        model.addAttribute("currentPage", pageResult.getNumber());
        model.addAttribute("totalPages", pageResult.getTotalPages());
        model.addAttribute("totalElements", pageResult.getTotalElements());
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("pageSizes", PAGE_SIZE_OPTIONS);
        model.addAttribute("pageNumbers", pageNumbers);
        model.addAttribute("hasPrevious", pageResult.hasPrevious());
        model.addAttribute("hasNext", pageResult.hasNext());
        model.addAttribute("startItem", pageResult.getTotalElements() == 0
                ? 0
                : pageResult.getNumber() * pageResult.getSize() + 1);
        model.addAttribute("endItem",
                pageResult.getNumber() * pageResult.getSize() + pageResult.getNumberOfElements());

        return "dashboard";
    }

    @PostMapping("/shipments")
    public String registerShipment(@RequestParam String trackingNumber,
                                   @RequestParam(defaultValue = "ONE") Carrier carrier,
                                   RedirectAttributes redirectAttributes) {
        try {
            boolean alreadyRegistered = shipmentService.existsByTrackingNumber(trackingNumber);
            Shipment registered = shipmentService.registerShipment(trackingNumber, carrier);
            if (alreadyRegistered) {
                redirectAttributes.addFlashAttribute("warningMessage",
                        "La guía " + registered.getTrackingNumber() + " ya estaba registrada.");
            } else {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Guía " + registered.getTrackingNumber() + " registrada con éxito.");
            }
        } catch (Exception ex) {
            log.error("Error al registrar guía individual: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Error al registrar la guía: " + ex.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/shipments/upload-excel")
    public String uploadExcel(@RequestParam("file") MultipartFile file,
                              @RequestParam(defaultValue = "ONE") Carrier carrier,
                              RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Debe seleccionar un archivo Excel (.xlsx)");
            return "redirect:/";
        }

        try {
            BatchImportSummary summary = shipmentService.importFromExcel(file.getInputStream(), carrier);
            String message = String.format("Excel procesado: %d guías detectadas, %d nuevas añadidas, %d duplicadas omitidas.",
                    summary.getTotalProcessed(), summary.getAddedCount(), summary.getDuplicateCount());
            redirectAttributes.addFlashAttribute("successMessage", message);

            if (!summary.getErrorMessages().isEmpty()) {
                redirectAttributes.addFlashAttribute("warningMessage",
                        "Advertencias en filas: " + String.join(", ", summary.getErrorMessages()));
            }
        } catch (Exception ex) {
            log.error("Error al procesar archivo Excel: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Fallo al leer archivo Excel: " + ex.getMessage());
        }

        return "redirect:/";
    }

    @PostMapping("/shipments/track-now")
    public String trackNow(RedirectAttributes redirectAttributes) {
        try {
            boolean started = trackingSchedulerService.startTrackingCycleAsync();
            if (started) {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Monitoreo iniciado en segundo plano. Puedes seguir usando la aplicación; el progreso se actualiza automáticamente.");
            } else {
                redirectAttributes.addFlashAttribute("warningMessage",
                        "Ya hay un monitoreo en progreso. Espera a que termine para iniciar otro.");
            }
        } catch (Exception ex) {
            log.error("Error al disparar monitoreo manual: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Error al iniciar monitoreo: " + ex.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/shipments/track-status")
    @ResponseBody
    public TrackingStatus trackStatus() {
        return trackingSchedulerService.getTrackingStatus();
    }

    @GetMapping("/shipments/{id}/history")
    public String viewHistory(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return shipmentService.getShipmentById(id)
                .map(shipment -> {
                    List<ShipmentEventLog> logs = shipmentService.getEventLogs(id);
                    model.addAttribute("shipment", shipment);
                    model.addAttribute("logs", logs);
                    return "history";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("errorMessage", "Embarque no encontrado (ID: " + id + ")");
                    return "redirect:/";
                });
    }

    @PostMapping("/shipments/{id}/delete")
    public String deleteShipment(@PathVariable Long id,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String search,
                                 RedirectAttributes redirectAttributes) {
        try {
            shipmentService.deleteShipment(id);
            redirectAttributes.addFlashAttribute("successMessage", "Embarque eliminado correctamente.");
        } catch (Exception ex) {
            log.error("Error al eliminar embarque ID {}: {}", id, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", "Error al eliminar embarque: " + ex.getMessage());
        }
        return redirectToDashboard(page, size, search);
    }

    private String redirectToDashboard(int page, int size, String search) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        if (search != null && !search.isBlank()) {
            builder.queryParam("search", search.trim());
        }

        return "redirect:" + builder.build().encode().toUriString();
    }
}
