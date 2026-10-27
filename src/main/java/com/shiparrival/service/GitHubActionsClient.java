package com.shiparrival.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente mínimo de la API de GitHub Actions.
 *
 * <p>Se usa para dos cosas: disparar el monitoreo desde el dashboard y averiguar
 * por qué un run no llegó a ejecutarse (por ejemplo, el paso de Tailscale falla
 * cuando el teléfono que sirve de salida está apagado).</p>
 *
 * <p>Todos los métodos degradan a vacío o {@code false} ante cualquier error:
 * el dashboard debe seguir funcionando aunque GitHub esté caído o el token haya
 * caducado.</p>
 */
@Slf4j
@Component
public class GitHubActionsClient {

    private static final String API = "https://api.github.com";

    private final boolean enabled;
    private final String token;
    private final String owner;
    private final String repo;
    private final String workflowFile;
    private final String ref;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public GitHubActionsClient(@Value("${github.dispatch.enabled:false}") boolean enabled,
                               @Value("${github.dispatch.token:}") String token,
                               @Value("${github.dispatch.owner:krcondorig}") String owner,
                               @Value("${github.dispatch.repo:ship-arrival}") String repo,
                               @Value("${github.dispatch.workflow-file:daily-monitoring.yml}") String workflowFile,
                               @Value("${github.dispatch.ref:master}") String ref,
                               RestTemplateBuilder restTemplateBuilder) {
        this(enabled, token, owner, repo, workflowFile, ref, restTemplateBuilder.build());
    }

    GitHubActionsClient(boolean enabled,
                        String token,
                        String owner,
                        String repo,
                        String workflowFile,
                        String ref,
                        RestTemplate restTemplate) {
        this.enabled = enabled;
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.workflowFile = workflowFile;
        this.ref = ref;
        this.restTemplate = restTemplate;
    }

    public boolean isEnabled() {
        return enabled && token != null && !token.isBlank();
    }

    /**
     * @return la ejecución más reciente del workflow, si hay alguna.
     */
    public Optional<GitHubRunState> findLatestRun() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            JsonNode root = get(API + "/repos/" + owner + "/" + repo
                    + "/actions/workflows/" + workflowFile + "/runs?per_page=5");
            JsonNode runs = root.path("workflow_runs");
            if (!runs.isArray() || runs.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toState(runs.get(0)));
        } catch (Exception ex) {
            log.warn("No se pudo consultar la última ejecución de GitHub: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<GitHubRunState> findRun(long runId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toState(get(API + "/repos/" + owner + "/" + repo
                    + "/actions/runs/" + runId)));
        } catch (Exception ex) {
            log.warn("No se pudo consultar la ejecución {} de GitHub: {}", runId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @return el nombre del primer paso que falló, para explicar la causa.
     */
    public Optional<String> findFailedStep(long runId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            JsonNode root = get(API + "/repos/" + owner + "/" + repo
                    + "/actions/runs/" + runId + "/jobs");
            for (JsonNode job : root.path("jobs")) {
                for (JsonNode step : job.path("steps")) {
                    if ("failure".equals(step.path("conclusion").asText())) {
                        return Optional.of(step.path("name").asText());
                    }
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("No se pudo consultar los pasos de la ejecución {}: {}", runId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Dispara el workflow pasando el identificador con el que el runner publicará
     * su avance.
     */
    public boolean dispatch(String runId) {
        if (!isEnabled()) {
            return false;
        }
        try {
            HttpHeaders headers = headers();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of(
                    "ref", ref,
                    "inputs", Map.of("run_id", runId));

            restTemplate.exchange(API + "/repos/" + owner + "/" + repo
                            + "/actions/workflows/" + workflowFile + "/dispatches",
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return true;
        } catch (Exception ex) {
            log.warn("No se pudo disparar el workflow de GitHub: {}", ex.getMessage());
            return false;
        }
    }

    private JsonNode get(String url) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        return objectMapper.readTree(response.getBody());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private GitHubRunState toState(JsonNode run) {
        return new GitHubRunState(
                run.path("id").asLong(),
                text(run, "status"),
                text(run, "conclusion"),
                text(run, "html_url"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
