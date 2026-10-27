package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.repository.ShipmentRepository;
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

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Test
    @DisplayName("Solo el administrador ve el enlace a la administración de usuarios")
    void shouldShowUsersLinkOnlyForAdmins() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("jefa").passwordHash("h").role(UserRole.ADMIN).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());
        AppUser user = repository.save(AppUser.builder()
                .username("analista").passwordHash("h").role(UserRole.USER).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/users\"")));

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/users\""))));
    }

    @Test
    @DisplayName("Un usuario normal no ve los formularios de administración")
    void shouldHideAdminFormsFromRegularUsers() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("beto").passwordHash("h").role(UserRole.USER).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"trackNowForm\""))))
                .andExpect(content().string(not(containsString("Registrar Embarque Individual"))));

        AppUser admin = repository.save(AppUser.builder()
                .username("jefa2").passwordHash("h").role(UserRole.ADMIN).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trackNowForm\"")))
                .andExpect(content().string(containsString("Registrar Embarque Individual")));
    }

    @Test
    @DisplayName("El nombre del usuario aparece en la barra superior")
    void shouldShowCurrentUsername() throws Exception {
        AppUser user = repository.save(AppUser.builder()
                .username("carla").passwordHash("h").role(UserRole.USER).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, user.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("carla")));
    }

    @Test
    @DisplayName("El operador ve registro, importación, monitoreo y borrado, pero no la administración de cuentas")
    void shouldShowOperationalActionsToOperator() throws Exception {
        AppUser operator = repository.save(AppUser.builder()
                .username("operador").passwordHash("h").role(UserRole.OPERADOR).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());
        Shipment shipment = shipmentRepository.save(Shipment.builder()
                .trackingNumber("TESTBL700001").carrier(Carrier.ONE).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, operator.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trackNowForm\"")))
                .andExpect(content().string(containsString("Registrar Embarque Individual")))
                .andExpect(content().string(containsString("Carga Masiva desde Excel")))
                .andExpect(content().string(containsString("/shipments/" + shipment.getId() + "/delete")))
                .andExpect(content().string(not(containsString("href=\"/users\""))));
    }
}
