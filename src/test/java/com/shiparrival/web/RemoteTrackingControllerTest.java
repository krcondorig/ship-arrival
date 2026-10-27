package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.service.GitHubActionsClient;
import com.shiparrival.service.GitHubRunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "github.dispatch.enabled=true",
        "github.dispatch.token=token-de-prueba",
        "spring.datasource.url=jdbc:h2:mem:remote;DB_CLOSE_DELAY=-1"
})
class RemoteTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private GitHubActionsClient gitHubActionsClient;

    private AppUser givenAdmin() {
        return repository.findByUsernameIgnoreCase("root").orElseGet(() ->
                repository.save(AppUser.builder()
                        .username("root").passwordHash(passwordEncoder.encode("claveSegura1"))
                        .role(UserRole.ADMIN).enabled(true).build()));
    }

    @Test
    @DisplayName("El botón dispara el workflow y redirige con el identificador de la ejecución")
    void shouldDispatchWorkflow() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.dispatch()).thenReturn(Optional.of(987654L));

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?runId=987654"));
    }

    @Test
    @DisplayName("Si el teléfono no responde, el mensaje lo dice")
    void shouldReportPhoneOffline() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findRun(987654L)).thenReturn(Optional.of(
                new GitHubRunState(987654L, "completed", "failure",
                        "https://github.com/x/y/actions/runs/987654", "Activate Phone Exit Node")));

        mockMvc.perform(get("/shipments/live-status").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "Tu teléfono no responde como nodo de salida. Verifica que esté encendido, con datos móviles y Tailscale activo."));
    }

    @Test
    @DisplayName("Mientras GitHub prepara el run, la fase es PREPARING sin porcentaje")
    void shouldReportPreparing() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findRun(987654L)).thenReturn(Optional.of(
                new GitHubRunState(987654L, "in_progress", null,
                        "https://github.com/x/y/actions/runs/987654", null)));

        mockMvc.perform(get("/shipments/live-status").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(jsonPath("$.phase").value("PREPARING"))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("No dispara si ya hay una ejecución en curso")
    void shouldRefuseWhenRunAlreadyInProgress() throws Exception {
        AppUser admin = givenAdmin();
        when(gitHubActionsClient.isEnabled()).thenReturn(true);
        when(gitHubActionsClient.findLatestRun()).thenReturn(Optional.of(
                new GitHubRunState(111L, "in_progress", null,
                        "https://github.com/x/y/actions/runs/111", null)));

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("warningMessage",
                        "Ya hay un monitoreo en curso. Espera a que termine."));

        verify(gitHubActionsClient, never()).dispatch();
    }

    @Test
    @DisplayName("Un usuario normal no puede disparar el monitoreo")
    void shouldForbidRegularUser() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.USER).enabled(true).build());

        mockMvc.perform(post("/shipments/track-now")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isForbidden());
    }
}
