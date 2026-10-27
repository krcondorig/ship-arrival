package com.shiparrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubActionsClientTest {

    private static final String RUNS_URL =
            "https://api.github.com/repos/krcondorig/ship-arrival/actions/workflows/daily-monitoring.yml/runs?per_page=5";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    private GitHubActionsClient client(boolean enabled, String token) {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        return new GitHubActionsClient(enabled, token, "krcondorig", "ship-arrival",
                "daily-monitoring.yml", "master", restTemplate);
    }

    @Test
    @DisplayName("Sin token habilitado no consulta la API")
    void shouldBeDisabledWithoutToken() {
        GitHubActionsClient client = client(true, "");

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.findLatestRun()).isEmpty();
        assertThat(client.dispatch("run-1")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("Devuelve la última ejecución del workflow")
    void shouldReturnLatestRun() {
        GitHubActionsClient client = client(true, "ghp_token");

        server.expect(once(), requestTo(RUNS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer ghp_token"))
                .andRespond(withSuccess("""
                        {"workflow_runs":[
                          {"id": 9001, "status": "completed", "conclusion": "success",
                           "html_url": "https://github.com/krcondorig/ship-arrival/actions/runs/9001"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Optional<GitHubRunState> run = client.findLatestRun();

        assertThat(run).isPresent();
        assertThat(run.get().id()).isEqualTo(9001L);
        assertThat(run.get().status()).isEqualTo("completed");
        assertThat(run.get().conclusion()).isEqualTo("success");
        assertThat(run.get().isSuccess()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Sin ejecuciones devuelve vacío")
    void shouldReturnEmptyWhenNoRuns() {
        GitHubActionsClient client = client(true, "ghp_token");

        server.expect(once(), requestTo(RUNS_URL))
                .andRespond(withSuccess("{\"workflow_runs\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findLatestRun()).isEmpty();
    }

    @Test
    @DisplayName("Identifica el paso que falló")
    void shouldFindFailedStep() {
        GitHubActionsClient client = client(true, "ghp_token");

        server.expect(once(), requestTo(
                        "https://api.github.com/repos/krcondorig/ship-arrival/actions/runs/9001/jobs"))
                .andRespond(withSuccess("""
                        {"jobs":[{"name":"monitor","steps":[
                          {"name":"Checkout", "conclusion":"success"},
                          {"name":"Connect Tailscale", "conclusion":"failure"}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findFailedStep(9001L)).contains("Connect Tailscale");
    }

    @Test
    @DisplayName("Dispara el workflow con el identificador de ejecución")
    void shouldDispatchWorkflow() {
        GitHubActionsClient client = client(true, "ghp_token");

        server.expect(once(), requestTo(
                        "https://api.github.com/repos/krcondorig/ship-arrival/actions/workflows/daily-monitoring.yml/dispatches"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"ref\":\"master\",\"inputs\":{\"run_id\":\"run-1\"}}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.dispatch("run-1")).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Un error de la API no rompe el dashboard")
    void shouldSwallowApiErrors() {
        GitHubActionsClient client = client(true, "ghp_token");

        server.expect(once(), requestTo(RUNS_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.findLatestRun()).isEmpty();
    }
}
