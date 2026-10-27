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
    private static final int DISPATCH_POLL_ATTEMPTS = 5;
    private static final long DISPATCH_POLL_INTERVAL_MS = 1500L;

    private final boolean enabled;
    private final String token;
    private final String owner;
    private final String repo;
    private final String workflowFile;
    private final String ref;
    private final RestTemplate restTemplate;
    private final Sleeper sleeper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public GitHubActionsClient(@Value("${github.dispatch.enabled:false}") boolean enabled,
                               @Value("${github.dispatch.token:}") String token,
                               @Value("${github.dispatch.owner:krcondorig}") String owner,
                               @Value("${github.dispatch.repo:ship-arrival}") String repo,
                               @Value("${github.dispatch.workflow-file:daily-monitoring.yml}") String workflowFile,
                               @Value("${github.dispatch.ref:master}") String ref,
                               RestTemplateBuilder restTemplateBuilder) {
        this(enabled, token, owner, repo, workflowFile, ref,
                restTemplateBuilder.build(), Thread::sleep);
    }

    GitHubActionsClient(boolean enabled,
                        String token,
                        String owner,
                        String repo,
                        String workflowFile,
                        String ref,
                        RestTemplate restTemplate) {
        this(enabled, token, owner, repo, workflowFile, ref, restTemplate, Thread::sleep);
    }

    GitHubActionsClient(boolean enabled,
                        String token,
                        String owner,
                        String repo,
                        String workflowFile,
                        String ref,
                        RestTemplate restTemplate,
                        Sleeper sleeper) {
        this.enabled = enabled;
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.workflowFile = workflowFile;
        this.ref = ref;
        this.restTemplate = restTemplate;
        this.sleeper = sleeper;
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
     * Dispara el workflow y espera a que GitHub registre la ejecución.
     *
     * <p>La API de {@code dispatches} responde 204 sin cuerpo, así que el
     * identificador solo se obtiene consultando la lista de ejecuciones hasta que
     * aparece una nueva.</p>
     *
     * @return el identificador de la ejecución recién creada, si se pudo averiguar.
     */
    public Optional<Long> dispatch() {
        if (!isEnabled()) {
            return Optional.empty();
        }

        long previousId = findLatestRun().map(GitHubRunState::id).orElse(0L);

        try {
            HttpHeaders headers = headers();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(API + "/repos/" + owner + "/" + repo
                            + "/actions/workflows/" + workflowFile + "/dispatches",
                    HttpMethod.POST, new HttpEntity<>(Map.of("ref", ref), headers), String.class);
        } catch (Exception ex) {
            log.warn("No se pudo disparar el workflow de GitHub: {}", ex.getMessage());
            return Optional.empty();
        }

        try {
            for (int attempt = 0; attempt < DISPATCH_POLL_ATTEMPTS; attempt++) {
                Optional<GitHubRunState> latest = findLatestRun();
                if (latest.isPresent() && latest.get().id() != previousId) {
                    return Optional.of(latest.get().id());
                }
                sleeper.sleep(DISPATCH_POLL_INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Se interrumpió la espera del identificador de la ejecución");
        }

        log.warn("El workflow se disparó pero GitHub no reportó la nueva ejecución a tiempo");
        return Optional.empty();
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
                text(run, "html_url"),
                null);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * Permite sustituir la espera real por una nula en las pruebas.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
