package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:authcontroller;DB_CLOSE_DELAY=-1"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void givenUser(String username, String rawPassword, UserRole role) {
        repository.save(AppUser.builder()
                .username(username).passwordHash(passwordEncoder.encode(rawPassword))
                .role(role).enabled(true).build());
    }

    @Test
    @DisplayName("La pantalla de login es pública y se renderiza")
    void shouldRenderLoginScreen() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    @DisplayName("Credenciales correctas inician sesión y redirigen a la raíz")
    void shouldLoginSuccessfully() throws Exception {
        givenUser("ana", "claveSegura1", UserRole.USER);

        mockMvc.perform(post("/login")
                        .param("username", "ana")
                        .param("password", "claveSegura1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(request().sessionAttribute(AuthFilter.SESSION_USER_ID, notNullValue()))
                .andExpect(request().sessionAttribute(AuthFilter.SESSION_EPOCH, 1L));

        assertThat(repository.findByUsernameIgnoreCase("ana").orElseThrow().getActiveSessionExpiresAt())
                .isAfter(java.time.LocalDateTime.now().plusMinutes(55));
    }

    @Test
    @DisplayName("Rechaza otro inicio cuando ya existe una sesión vigente")
    void shouldRejectLoginWhenAnotherSessionIsActive() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana-activa").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.USER).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(11)).build());

        mockMvc.perform(post("/login")
                        .param("username", "ana-activa")
                        .param("password", "claveSegura1"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("errorMessage",
                        "Esta cuenta ya tiene una sesión abierta. Cierra sesión en el otro dispositivo o pide a un administrador que la libere."));

        assertThat(repository.findById(user.getId()).orElseThrow().getSessionEpoch()).isZero();
    }

    @Test
    @DisplayName("Credenciales incorrectas vuelven al login con mensaje genérico")
    void shouldRejectWrongPassword() throws Exception {
        givenUser("beto", "claveSegura1", UserRole.USER);

        mockMvc.perform(post("/login")
                        .param("username", "beto")
                        .param("password", "incorrecta"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("errorMessage",
                        "Usuario o contraseña incorrectos."));
    }

    @Test
    @DisplayName("Tras 5 fallos, el login queda bloqueado")
    void shouldBlockAfterFiveFailures() throws Exception {
        givenUser("carla", "claveSegura1", UserRole.USER);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login")
                    .param("username", "carla")
                    .param("password", "incorrecta"));
        }

        mockMvc.perform(post("/login")
                        .param("username", "carla")
                        .param("password", "claveSegura1"))
                .andExpect(view().name("login"))
                .andExpect(model().attribute("errorMessage",
                        "Demasiados intentos fallidos. Espera 15 minutos."));
    }

    @Test
    @DisplayName("Rechaza redirecciones a sitios externos")
    void shouldRejectOpenRedirect() throws Exception {
        givenUser("dario", "claveSegura1", UserRole.USER);
        org.springframework.mock.web.MockHttpSession currentSession = new org.springframework.mock.web.MockHttpSession();

        mockMvc.perform(post("/login").session(currentSession)
                        .param("username", "dario")
                        .param("password", "claveSegura1")
                        .param("redirect", "https://sitio-malicioso.example"))
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/logout").session(currentSession));
        currentSession = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(post("/login").session(currentSession)
                        .param("username", "dario")
                        .param("password", "claveSegura1")
                        .param("redirect", "//sitio-malicioso.example"))
                .andExpect(redirectedUrl("/"));

        // Los navegadores normalizan "\" a "/", así que "/\sitio.example" acabaría
        // siendo "//sitio.example".
        mockMvc.perform(post("/logout").session(currentSession));
        currentSession = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(post("/login").session(currentSession)
                        .param("username", "dario")
                        .param("password", "claveSegura1")
                        .param("redirect", "/\\sitio-malicioso.example"))
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/logout").session(currentSession));
        currentSession = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(post("/login").session(currentSession)
                        .param("username", "dario")
                        .param("password", "claveSegura1")
                        .param("redirect", "javascript:alert(1)"))
                .andExpect(redirectedUrl("/"));

        // Un destino interno sí se respeta tras cerrar la sesión anterior.
        mockMvc.perform(post("/logout").session(currentSession));
        currentSession = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(post("/login").session(currentSession)
                        .param("username", "dario")
                        .param("password", "claveSegura1")
                        .param("redirect", "/users"))
                .andExpect(redirectedUrl("/users"));
    }

    @Test
    @DisplayName("El identificador de sesión cambia tras iniciar sesión")
    void shouldChangeSessionIdOnLogin() throws Exception {
        givenUser("elena", "claveSegura1", UserRole.USER);

        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mockMvc.perform(post("/login").session(session)
                        .param("username", "elena")
                        .param("password", "claveSegura1"))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getId()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("Cerrar sesión invalida la sesión y vuelve al login")
    void shouldLogout() throws Exception {
        givenUser("fabi", "claveSegura1", UserRole.USER);
        AppUser user = repository.findByUsernameIgnoreCase("fabi").orElseThrow();
        user.setActiveSessionExpiresAt(java.time.LocalDateTime.now().plusHours(2));
        repository.save(user);
        Long userId = repository.findByUsernameIgnoreCase("fabi").orElseThrow().getId();

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthFilter.SESSION_USER_ID, userId);
        session.setAttribute(AuthFilter.SESSION_EPOCH, user.getSessionEpoch());

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(repository.findById(userId).orElseThrow().getActiveSessionExpiresAt()).isNull();
    }
}
