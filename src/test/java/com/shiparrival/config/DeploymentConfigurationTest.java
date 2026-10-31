package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentConfigurationTest {

    @Test
    @DisplayName("El diagnóstico de red es manual, aislado y compara ambas rutas para ONE, MSC y MAERSK")
    void carrierNetworkDiagnosticIsIsolatedAndManualOnly() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/carrier-network-diagnostic.yml"));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .contains("without-tailscale:")
                .contains("with-phone-exit-node:")
                .contains("CarrierNetworkDiagnosticTest")
                .contains("CARRIER_NETWORK_DIAGNOSTIC")
                .contains("run: ./mvnw -B -DskipTests test")
                .contains("--exit-node=100.77.93.94")
                .contains("xvfb-run -a ./mvnw -B -o -Dtest=CarrierNetworkDiagnosticTest test")
                .contains("timeout-minutes: 20");

        assertThat(workflow)
                .doesNotContain("--tracking.run-once")
                .doesNotContain("HAPAG_LLOYD")
                .doesNotContain("SUPABASE_DB_PASSWORD")
                .doesNotContain("ZENROWS_API_KEY");
        assertPrefetchOccursBeforeDiagnostics(workflow, "without-tailscale:", "with-phone-exit-node:");
    }

    private void assertPrefetchOccursBeforeDiagnostics(String workflow, String... jobMarkers) {
        for (String jobMarker : jobMarkers) {
            int jobStart = workflow.indexOf(jobMarker);
            int diagnostics = workflow.indexOf("-Dtest=CarrierNetworkDiagnosticTest", jobStart);
            int prefetch = workflow.indexOf("./mvnw -B -DskipTests test", jobStart);
            assertThat(jobStart).as("job %s exists", jobMarker).isGreaterThanOrEqualTo(0);
            assertThat(prefetch).as("job %s prefetches Maven dependencies", jobMarker)
                    .isGreaterThan(jobStart);
            assertThat(diagnostics).as("job %s runs diagnostics", jobMarker).isGreaterThan(prefetch);
        }
    }

    @Test
    @DisplayName("El workflow pasa el identificador del run al ciclo de monitoreo")
    void workflowShouldPassRunId() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));

        assertThat(workflow).contains("TRACKING_RUN_ID: ${{ github.run_id }}");
        assertThat(workflow).contains("TRACKING_ORIGIN: ci");
    }

    @Test
    @DisplayName("El workflow usa ZenRows solo para disparos manuales y conserva Hapag-Lloyd manual en el cron")
    void workflowEnablesHapagLloydOnlyForManualDispatch() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));

        assertThat(workflow).contains("ZENROWS_API_KEY: ${{ secrets.ZENROWS_API_KEY }}");
        assertThat(workflow).contains("TRACKING_MANUAL_CHECK_CARRIERS: ${{ github.event_name == 'schedule' && 'HAPAG_LLOYD' || '' }}");
        assertThat(workflow).doesNotContain("echo $ZENROWS_API_KEY");
    }

    @Test
    @DisplayName("Render declara las variables de autenticación y del disparo remoto")
    void renderShouldDeclareRequiredEnvVars() throws IOException {
        String render = Files.readString(Path.of("render.yaml"));

        assertThat(render).contains("ADMIN_USERNAME");
        assertThat(render).contains("ADMIN_PASSWORD");
        assertThat(render).contains("GITHUB_DISPATCH_ENABLED");
        assertThat(render).contains("GITHUB_TOKEN");
    }
}
