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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:dashboarduser;DB_CLOSE_DELAY=-1"
})
class DashboardUserRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Test
    @DisplayName("Solo el administrador ve el enlace a la administración de usuarios")
    void shouldShowUsersLinkOnlyForAdmins() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("jefa").passwordHash("h").role(UserRole.ADMIN).enabled(true).build());
        AppUser user = repository.save(AppUser.builder()
                .username("analista").passwordHash("h").role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/users\"")));

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/users\""))));
    }

    @Test
    @DisplayName("El nombre del usuario aparece en la barra superior")
    void shouldShowCurrentUsername() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("carla").passwordHash("h").role(UserRole.USER).enabled(true).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("carla")));
    }
}
