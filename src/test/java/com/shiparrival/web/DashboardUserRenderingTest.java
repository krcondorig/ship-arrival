package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private ShipmentEventLogRepository shipmentEventLogRepository;

    /**
     * La base de esta clase es compartida por todos los métodos y el dashboard
     * pagina de 10 en 10: si los embarques se acumulan, una guía puede caer a la
     * página 2 y las aserciones sobre su enlace de borrado fallarían según el
     * orden en que JUnit ejecute los tests. Se limpia entre métodos respetando la
     * clave foránea de {@code ShipmentEventLog} hacia {@code Shipment}.
     *
     * <p>Deliberadamente no se usa {@code @Transactional} a nivel de clase: con
     * {@code @SpringBootTest} + MockMvc, el {@code AuthFilter} lee el usuario en
     * cada petición y el rollback por método lo dejaría sin ver las filas,
     * devolviendo redirecciones a {@code /login}.</p>
     */
    @AfterEach
    void cleanDatabase() {
        shipmentEventLogRepository.deleteAll();
        shipmentRepository.deleteAll();
        repository.deleteAll();
    }

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
                .andExpect(content().string(not(containsString("id=\"modalNewShipment\""))));

        AppUser admin = repository.save(AppUser.builder()
                .username("jefa2").passwordHash("h").role(UserRole.ADMIN).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trackNowForm\"")))
                .andExpect(content().string(containsString("id=\"modalNewShipment\"")))
                .andExpect(content().string(containsString("Nuevo Embarque")));
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
    @DisplayName("La columna Acciones solo ofrece borrar en guías pendientes o con error")
    void shouldShowDeleteActionOnlyForDeletableStatuses() throws Exception {
        AppUser operator = repository.save(AppUser.builder()
                .username("operador-borrado").passwordHash("h").role(UserRole.OPERADOR).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());
        Shipment pending = shipmentRepository.save(Shipment.builder()
                .trackingNumber("TESTBL700101").carrier(Carrier.ONE)
                .status(ShipmentStatus.PENDING_FIRST_CHECK).build());
        Shipment inTransit = shipmentRepository.save(Shipment.builder()
                .trackingNumber("TESTBL700102").carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, operator.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/shipments/" + pending.getId() + "/delete")))
                .andExpect(content().string(containsString("/shipments/" + inTransit.getId() + "/history")))
                .andExpect(content().string(not(containsString("/shipments/" + inTransit.getId() + "/delete"))));
    }

    @Test
    @DisplayName("El operador ve registro, importación, monitoreo y borrado, pero no la administración de cuentas")
    void shouldShowOperationalActionsToOperator() throws Exception {
        AppUser operator = repository.save(AppUser.builder()
                .username("operador").passwordHash("h").role(UserRole.OPERADOR).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());
        Shipment shipment = shipmentRepository.save(Shipment.builder()
                .trackingNumber("TESTBL700001").carrier(Carrier.ONE)
                .status(ShipmentStatus.ERROR_CHECKING).build());

        mockMvc.perform(get("/").sessionAttr(AuthFilter.SESSION_USER_ID, operator.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trackNowForm\"")))
                .andExpect(content().string(containsString("Nuevo Embarque")))
                .andExpect(content().string(containsString("Carga Masiva Excel")))
                .andExpect(content().string(containsString("/shipments/" + shipment.getId() + "/delete")))
                .andExpect(content().string(not(containsString("href=\"/users\""))));
    }

    @Test
    @DisplayName("Un administrador puede eliminar una guía pendiente desde el dashboard")
    void shouldAllowAdminToDeletePendingShipment() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("admin-borrado").passwordHash("h").role(UserRole.ADMIN).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());
        Shipment pending = shipmentRepository.save(Shipment.builder()
                .trackingNumber("TESTBL700202").carrier(Carrier.ONE)
                .status(ShipmentStatus.PENDING_FIRST_CHECK).build());

        mockMvc.perform(post("/shipments/" + pending.getId() + "/delete")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().is3xxRedirection());

        assertThat(shipmentRepository.findById(pending.getId())).isEmpty();
    }
}
