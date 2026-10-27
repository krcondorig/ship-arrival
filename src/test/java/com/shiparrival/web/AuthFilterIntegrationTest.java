package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:authfilter;DB_CLOSE_DELAY=-1"
})
class AuthFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Test
    @DisplayName("Sin sesión, la raíz redirige al login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("Los recursos estáticos son públicos")
    void shouldAllowStaticResources() throws Exception {
        mockMvc.perform(get("/css/dashboard.css")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Con sesión válida se accede al dashboard")
    void shouldAllowAuthenticatedUser() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("ana").passwordHash("h").role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(request().attribute(AuthFilter.CURRENT_USER_ATTRIBUTE,
                        new CurrentUser(user.getId(), "ana", UserRole.USER)));
    }

    @Test
    @DisplayName("Un usuario desactivado pierde el acceso aunque tenga sesión")
    void shouldRejectDisabledUserWithSession() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("beto").passwordHash("h").role(UserRole.USER).enabled(false).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("Cambiar la contraseña invalida las sesiones abiertas con la anterior")
    void shouldRejectSessionOpenedBeforePasswordReset() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("carla").passwordHash("h").role(UserRole.USER).enabled(true).build());

        // La sesión se abrió con la época 0; el administrador restablece la clave
        // y la cuenta pasa a la época 1.
        user.setSessionEpoch(1L);
        repository.save(user);

        mockMvc.perform(get("/")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, user.getId())
                        .sessionAttr(AuthFilter.SESSION_EPOCH, 0L))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, user.getId())
                        .sessionAttr(AuthFilter.SESSION_EPOCH, 1L))
                .andExpect(status().isOk());
    }
}
