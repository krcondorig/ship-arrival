package com.shiparrival;

import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * Ejecuta un único ciclo de monitoreo y termina el proceso.
 *
 * <p>Se activa con {@code tracking.run-once=true} y está pensado para
 * ejecuciones desatendidas en la nube (por ejemplo, GitHub Actions), donde no
 * existe un usuario navegando el dashboard y el proceso debe finalizar solo.</p>
 *
 * <p>El código de salida refleja el resultado: {@code 0} si al menos una guía se
 * actualizó o no había nada que monitorear, {@code 1} si todas fallaron o el
 * ciclo no pudo ejecutarse.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tracking.run-once", havingValue = "true")
public class TrackingRunOnceRunner implements ApplicationRunner {

    private final TrackingSchedulerService trackingSchedulerService;
    private final IntConsumer exitAction;

    @Autowired
    public TrackingRunOnceRunner(TrackingSchedulerService trackingSchedulerService,
                                 ApplicationContext applicationContext) {
        this(trackingSchedulerService,
                code -> System.exit(SpringApplication.exit(applicationContext, () -> code)));
    }

    TrackingRunOnceRunner(TrackingSchedulerService trackingSchedulerService,
                          IntConsumer exitAction) {
        this.trackingSchedulerService = trackingSchedulerService;
        this.exitAction = exitAction;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Modo de ejecución única activado: se monitorearán los embarques y el proceso finalizará");

        TrackingExecutionSummary summary;
        try {
            summary = trackingSchedulerService.runTrackingCycle();
        } catch (RuntimeException ex) {
            log.error("El ciclo de monitoreo falló de forma inesperada: {}", ex.getMessage(), ex);
            exitAction.accept(1);
            return;
        }

        log.info("Resumen del monitoreo: procesados={}, actualizados={}, fallidos={}, alertas={}, duración={} ms",
                summary.getTotalProcessed(),
                summary.getUpdatedCount(),
                summary.getFailedCount(),
                summary.getAlertsSentCount(),
                summary.getExecutionDurationMs());

        exitAction.accept(resolveExitCode(summary));
    }

    static int resolveExitCode(TrackingExecutionSummary summary) {
        if (summary.getErrorMessage() != null && !summary.getErrorMessage().isBlank()) {
            return 1;
        }
        if (summary.getTotalProcessed() == 0) {
            return 0;
        }
        if (summary.getUpdatedCount() == 0 && summary.getFailedCount() > 0) {
            return 1;
        }
        return 0;
    }
}
