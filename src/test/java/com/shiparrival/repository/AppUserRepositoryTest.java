package com.shiparrival.repository;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AppUserRepositoryTest {

    @Autowired
    private AppUserRepository repository;

    @Test
    @DisplayName("Guarda un usuario y lo recupera sin distinguir mayúsculas")
    void shouldFindByUsernameIgnoringCase() {
        repository.save(AppUser.builder()
                .username("admin").passwordHash("hash").role(UserRole.ADMIN).enabled(true).build());

        assertThat(repository.findByUsernameIgnoreCase("ADMIN")).isPresent();
        assertThat(repository.findByUsernameIgnoreCase("Admin")).isPresent();
    }

    @Test
    @DisplayName("El nombre de usuario es único")
    void shouldRejectDuplicateUsername() {
        repository.saveAndFlush(AppUser.builder()
                .username("admin").passwordHash("hash").role(UserRole.ADMIN).enabled(true).build());

        assertThatThrownBy(() -> repository.saveAndFlush(AppUser.builder()
                .username("admin").passwordHash("otro").role(UserRole.USER).enabled(true).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Renueva sesión solo con época coincidente y sesión todavía vigente")
    void shouldExtendOnlyCurrentUnexpiredSession() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        AppUser user = repository.saveAndFlush(AppUser.builder()
                .username("session-user").passwordHash("hash").role(UserRole.USER).enabled(true)
                .sessionEpoch(3L).activeSessionExpiresAt(now.plusMinutes(5)).build());

        assertThat(repository.extendActiveSession(user.getId(), 3L, now, now.plusHours(1))).isEqualTo(1);
        assertThat(repository.extendActiveSession(user.getId(), 2L, now, now.plusHours(2))).isZero();
        assertThat(repository.extendActiveSession(user.getId(), 3L, now.plusHours(2), now.plusHours(3))).isZero();
    }

    @Test
    @DisplayName("Cuenta solo administradores activos")
    void shouldCountEnabledAdmins() {
        repository.save(AppUser.builder()
                .username("a1").passwordHash("h").role(UserRole.ADMIN).enabled(true).build());
        repository.save(AppUser.builder()
                .username("a2").passwordHash("h").role(UserRole.ADMIN).enabled(false).build());
        repository.save(AppUser.builder()
                .username("u1").passwordHash("h").role(UserRole.USER).enabled(true).build());

        assertThat(repository.countByRoleAndEnabledTrue(UserRole.ADMIN)).isEqualTo(1);
    }
}
