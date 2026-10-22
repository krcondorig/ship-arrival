package com.shiparrival.web;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.service.ShipmentService;
import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.BatchImportSummary;
import com.shiparrival.service.dto.ShipmentDto;
import com.shiparrival.service.dto.TrackingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Controller
@RequestMapping
@RequiredArgsConstructor
public class ShipmentWebController {

    private final ShipmentService shipmentService;
    private final TrackingSchedulerService trackingSchedulerService;

    @GetMapping({"/", "/shipments"})
    public String dashboard(@RequestParam(required = false) String search, Model model) {
        LocalDate today = LocalDate.now();
        List<Shipment> shipments;
        
        if (search != null && !search.trim().isEmpty()) {
            shipments = shipmentService.searchShipments(search.trim());
            model.addAttribute("searchQuery", search.trim());
        } else {
            shipments = shipmentService.getAllShipments();
        }

        List<ShipmentDto> dtos = shipments.stream().map(s -> ShipmentDto.builder()
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

        long inTransitCount = dtos.stream().filter(s -> s.getStatus() == ShipmentStatus.IN_TRANSIT).count();
        long nearArrivalCount = dtos.stream().filter(s -> s.getStatus() == ShipmentStatus.NEAR_ARRIVAL).count();
        long arrivedCount = dtos.stream().filter(s -> s.getStatus() == ShipmentStatus.ARRIVED).count();

        model.addAttribute("shipments", dtos);
        model.addAttribute("totalCount", dtos.size());
        model.addAttribute("inTransitCount", inTransitCount);
        model.addAttribute("nearArrivalCount", nearArrivalCount);
        model.addAttribute("arrivedCount", arrivedCount);
        model.addAttribute("carriers", Carrier.values());
        model.addAttribute("isTrackingInProgress", trackingSchedulerService.isTrackingInProgress());

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
    public String deleteShipment(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            shipmentService.deleteShipment(id);
            redirectAttributes.addFlashAttribute("successMessage", "Embarque eliminado correctamente.");
        } catch (Exception ex) {
            log.error("Error al eliminar embarque ID {}: {}", id, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", "Error al eliminar embarque: " + ex.getMessage());
        }
        return "redirect:/";
    }
}
