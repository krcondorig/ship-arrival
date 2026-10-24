package com.shiparrival.config;

import com.shiparrival.service.TrackingSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Slf4j
@Component
@Profile("prod")
public class TrackingCommandRunner implements CommandLineRunner {

    private final TrackingSchedulerService trackingSchedulerService;

    public TrackingCommandRunner(TrackingSchedulerService trackingSchedulerService) {
        this.trackingSchedulerService = trackingSchedulerService;
    }

    @Override
    public void run(String... args) {
        boolean runTracking = Arrays.asList(args).contains("--run-tracking");
        if (runTracking) {
            log.info("Ejecutando ciclo de monitoreo programado (GitHub Actions + Supabase)...");
            var summary = trackingSchedulerService.runTrackingCycle();
            log.info("Ciclo CLI finalizado. Procesados: {}, Duración: {}ms, Error: {}", 
                    summary.getTotalProcessed(), summary.getExecutionDurationMs(), summary.getErrorMessage());
            System.exit(0);
        }
    }
}
