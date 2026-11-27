package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentConfigurationTest {

    @Test
    @DisplayName("El paralelismo está activo en GitHub Actions pero deshabilitado por defecto en la app y en Render")
    void carrierParallelismIsEnabledInWorkflowAndDefaultsToDisabled() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));
        String render = Files.readString(Path.of("render.yaml"));

        assertThat(properties).contains("tracking.parallel.enabled=${TRACKING_PARALLEL_ENABLED:false}");
        assertThat(properties).contains("tracking.request.delay-ms=${TRACKING_REQUEST_DELAY_MS:2000}");
        assertThat(workflow).contains("TRACKING_PARALLEL_ENABLED: \"true\"");
        assertThat(workflow).contains("TRACKING_REQUEST_DELAY_MS: \"1000\"");
        assertThat(workflow).contains("-Dmaven.test.skip=true package");

        // El egreso ya no sale por el telefono: el runner levanta Cloudflare WARP en
        // modo proxy y el rastreo de Hapag-Lloyd apunta a ese SOCKS5 local.
        assertThat(workflow).contains("runs-on: ubuntu-24.04");
        assertThat(workflow).contains("warp-cli --accept-tos mode proxy");
        assertThat(workflow).contains("socks5://127.0.0.1:40000");
        assertThat(workflow).doesNotMatch("(?s).*--exit-node=\\d+\\.\\d+\\.\\d+\\.\\d+.*");

        // Se comprueba que no quede infraestructura de Tailscale, no la palabra suelta:
        // el paso de pre-descarga conserva el nombre historico "... before Tailscale".
        assertThat(workflow).doesNotContainIgnoringCase("tailscale/github-action");
        assertThat(workflow).doesNotContainIgnoringCase("tailscale set");
        assertThat(workflow).doesNotContainIgnoringCase("tailscale status");
        assertThat(workflow).doesNotContainIgnoringCase("tailscale down");
        assertThat(workflow).doesNotContain("TAILSCALE_");
        assertThat(workflow).doesNotContain("xvfb-run");

        assertThat(render).doesNotContain("TRACKING_PARALLEL_ENABLED");
    }

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
                .contains("test-compile dependency:resolve dependency:resolve-plugins")
                .contains("--exit-node=${{ secrets.TAILSCALE_EXIT_NODE_IP }}")
                .contains("xvfb-run -a ./mvnw -B -Dtest=CarrierNetworkDiagnosticTest test")
                .contains("timeout-minutes: 20")
                .doesNotMatch("(?s).*--exit-node=\\d+\\.\\d+\\.\\d+\\.\\d+.*");

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
            int prefetch = workflow.indexOf("test-compile dependency:resolve", jobStart);
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
    @DisplayName("El cron rastrea Hapag-Lloyd por Chrome sobre WARP y deja ZenRows como respaldo")
    void workflowTracksHapagLloydThroughWarpInScheduledRuns() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));

        assertThat(workflow).contains("ZENROWS_API_KEY: ${{ secrets.ZENROWS_API_KEY }}");
        assertThat(workflow).doesNotContain("echo $ZENROWS_API_KEY");

        // Hapag-Lloyd ya no se omite del rastreo automatico: el cliente Chrome lo
        // consulta por el SOCKS5 de WARP, medido contra el portal real en el run
        // 45950314181. Si alguien devuelve 'HAPAG_LLOYD' a esta lista, el cron
        // volveria a marcar las guias como verificacion manual y el adaptador
        // quedaria sin usar en silencio.
        assertThat(workflow).contains("TRACKING_MANUAL_CHECK_CARRIERS: \"\"");

        // El runner activa el cliente Chrome; Render, que no define la variable,
        // conserva ZenRows por el valor por defecto de la propiedad.
        assertThat(workflow).contains("HAPAG_LLOYD_CLIENT: \"chrome\"");
        assertThat(properties).contains("hapag-lloyd.client=${HAPAG_LLOYD_CLIENT:zenrows}");

        // El egreso sale por WARP y el navegador corre sin pantalla.
        assertThat(workflow).contains("HAPAG_LLOYD_PROXY: \"socks5://127.0.0.1:40000\"");
        assertThat(workflow).contains("HAPAG_LLOYD_HEADLESS: \"true\"");

        // Proteccion deliberada: en Render la lista por defecto mantiene Hapag
        // fuera del rastreo automatico, porque ahi no hay Chrome ni WARP.
        assertThat(properties).contains(
                "tracking.manual-check-carriers=${TRACKING_MANUAL_CHECK_CARRIERS:HAPAG_LLOYD}");
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

    @Test
    @DisplayName("El workflow pre-descarga ChromeDriver antes de WARP y desactiva telemetria/red en el ciclo")
    void workflowShouldPrefetchChromeDriverBeforeWarp() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/daily-monitoring.yml"));

        // El nombre del paso de pre-descarga conserva el literal historico
        // ("... before Tailscale") aunque ya no se use Tailscale.
        int prefetchIdx = workflow.indexOf("Prefetch ChromeDriver and resolve driver before Tailscale");
        int warpIdx = workflow.indexOf("Connect WARP through SOCKS5 proxy");
        int cycleIdx = workflow.indexOf("Run tracking cycle once");

        assertThat(prefetchIdx).as("ChromeDriver prefetch step exists").isGreaterThan(0);
        assertThat(warpIdx).as("WARP connection step exists").isGreaterThan(prefetchIdx);
        assertThat(cycleIdx).as("Tracking cycle step exists").isGreaterThan(warpIdx);

        assertThat(workflow).contains("SE_OFFLINE: \"true\"");
        assertThat(workflow).contains("SE_AVOID_STATS: \"true\"");
    }
}
