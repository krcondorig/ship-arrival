package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:livepanel;DB_CLOSE_DELAY=-1"
})
class DashboardLivePanelRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("El dashboard expone la URL del estado en vivo y el identificador del run")
    void shouldExposeLiveStatusUrl() throws Exception {
        AppUser admin = repository.save(AppUser.builder()
                .username("root").passwordHash(passwordEncoder.encode("claveSegura1"))
                .role(UserRole.ADMIN).enabled(true)
                .activeSessionExpiresAt(java.time.LocalDateTime.now().plusHours(1)).build());

        mockMvc.perform(get("/").param("runId", "987654")
                        .sessionAttr(AuthFilter.SESSION_USER_ID, admin.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-live-status-url")))
                .andExpect(content().string(containsString("data-run-id=\"987654\"")));
    }
}
