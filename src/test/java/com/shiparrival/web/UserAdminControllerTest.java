package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:usersadmin;DB_CLOSE_DELAY=-1"
})
class UserAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserService userService;

    private AppUser givenUser(String username, UserRole role) {
        return repository.save(AppUser.builder()
                .username(username).passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(role).enabled(true).build());
    }

    @Test
    @DisplayName("El panel de usuarios exige rol de administrador")
    void shouldRequireAdminRole() throws Exception {
        AppUser user = givenUser("regular", UserRole.USER);

        mockMvc.perform(get("/users").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("El operador no puede ver el panel de usuarios")
    void shouldForbidOperatorFromListingUsers() throws Exception {
        AppUser operator = givenUser("operador", UserRole.OPERADOR);

        mockMvc.perform(get("/users").sessionAttr(AuthFilter.SESSION_USER_ID, operator.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("El operador no puede crear cuentas")
    void shouldForbidOperatorFromCreatingUsers() throws Exception {
        AppUser operator = givenUser("operador2", UserRole.OPERADOR);

        mockMvc.perform(post("/users").sessionAttr(AuthFilter.SESSION_USER_ID, operator.getId())
                        .param("username", "intruso")
                        .param("password", "claveSegura1")
                        .param("role", "USER"))
                .andExpect(status().isForbidden());

        assertThat(userService.authenticate("intruso", "claveSegura1")).isEmpty();
    }

    @Test
    @DisplayName("Un administrador ve la lista de usuarios")
    void shouldShowUsersToAdmin() throws Exception {
        AppUser admin = givenUser("root", UserRole.ADMIN);

        mockMvc.perform(get("/users").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    @DisplayName("Un administrador crea una cuenta nueva")
    void shouldCreateUser() throws Exception {
        AppUser admin = givenUser("root2", UserRole.ADMIN);

        mockMvc.perform(post("/users").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId())
                        .param("username", "nuevo")
                        .param("password", "claveSegura1")
                        .param("role", "USER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users"));

        assertThat(userService.authenticate("nuevo", "claveSegura1")).isPresent();
    }

    @Test
    @DisplayName("Un administrador restablece una contraseña")
    void shouldResetPassword() throws Exception {
        AppUser admin = givenUser("root3", UserRole.ADMIN);
        AppUser target = givenUser("olvidadizo", UserRole.USER);

        mockMvc.perform(post("/users/" + target.getId() + "/password")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId())
                        .param("password", "otraClave1"))
                .andExpect(status().is3xxRedirection());

        assertThat(userService.authenticate("olvidadizo", "otraClave1")).isPresent();
        assertThat(userService.authenticate("olvidadizo", "claveSegura1")).isEmpty();
    }

    @Test
    @DisplayName("Un administrador desactiva una cuenta")
    void shouldToggleUser() throws Exception {
        AppUser admin = givenUser("root4", UserRole.ADMIN);
        AppUser target = givenUser("temporal", UserRole.USER);

        mockMvc.perform(post("/users/" + target.getId() + "/toggle")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(target.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Un administrador elimina una cuenta")
    void shouldDeleteUser() throws Exception {
        AppUser admin = givenUser("root5", UserRole.ADMIN);
        AppUser target = givenUser("sobrante", UserRole.USER);

        mockMvc.perform(post("/users/" + target.getId() + "/delete")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(target.getId())).isEmpty();
    }
}
