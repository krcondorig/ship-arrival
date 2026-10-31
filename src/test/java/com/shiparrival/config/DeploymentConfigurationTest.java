package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentConfigurationTest {

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
        assertThat(workflow).contains("TRACKING_MANUAL_CHECK_CARRIERS: ${{ github.event_name == 'workflow_dispatch' && '' || 'HAPAG_LLOYD' }}");
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
