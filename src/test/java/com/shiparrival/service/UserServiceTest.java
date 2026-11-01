package com.shiparrival.service;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private AppUserRepository repository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        userService = new UserService(repository, new BCryptPasswordEncoder(10));
    }

    @Test
    @DisplayName("La contraseña se guarda como hash BCrypt, nunca en claro")
    void shouldStoreHashedPassword() {
        AppUser created = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);

        assertThat(created.getPasswordHash()).isNotEqualTo("claveSegura1");
        assertThat(new BCryptPasswordEncoder().matches("claveSegura1", created.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("El nombre de usuario se normaliza a minúsculas y sin espacios")
    void shouldNormalizeUsername() {
        assertThat(userService.createUser("  Admin ", "claveSegura1", UserRole.ADMIN).getUsername())
                .isEqualTo("admin");
    }

    @Test
    @DisplayName("Rechaza contraseñas de menos de 8 caracteres")
    void shouldRejectShortPassword() {
        assertThatThrownBy(() -> userService.createUser("admin", "corta", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    @Test
    @DisplayName("Rechaza nombres de usuario duplicados")
    void shouldRejectDuplicateUsername() {
        when(repository.existsByUsernameIgnoreCase("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("admin", "claveSegura1", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Autentica con la contraseña correcta y rechaza la incorrecta")
    void shouldAuthenticate() {
        AppUser stored = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(stored));

        assertThat(userService.authenticate("admin", "claveSegura1")).isPresent();
        assertThat(userService.authenticate("admin", "incorrecta")).isEmpty();
    }

    @Test
    @DisplayName("Un usuario desactivado no puede autenticarse")
    void shouldRejectDisabledUser() {
        AppUser stored = userService.createUser("admin", "claveSegura1", UserRole.ADMIN);
        stored.setEnabled(false);
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(stored));

        assertThat(userService.authenticate("admin", "claveSegura1")).isEmpty();
    }

    @Test
    @DisplayName("Nadie puede desactivarse ni eliminarse a sí mismo")
    void shouldRejectSelfLockout() {
        assertThatThrownBy(() -> userService.setEnabled(7L, 7L, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> userService.deleteUser(7L, 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("No se puede degradar al último administrador activo")
    void shouldRejectDemotingLastAdmin() {
        AppUser lastAdmin = AppUser.builder()
                .id(1L).username("admin").passwordHash("h").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(repository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.changeRole(2L, 1L, UserRole.USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("administrador");
    }

    @Test
    @DisplayName("Un nuevo login reemplaza la sesión anterior e invalida su época")
    void shouldReplaceExistingSessionOnNewLogin() {
        AppUser user = AppUser.builder().id(9L).username("ana").passwordHash("h")
                .role(UserRole.USER).enabled(true)
                .sessionEpoch(4L)
                .activeSessionExpiresAt(java.time.LocalDateTime.of(2026, 10, 27, 13, 0))
                .build();
        when(repository.findByUsernameForUpdate("ana")).thenReturn(Optional.of(user));
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 27, 12, 0);

        assertThat(userService.startSession("ana", now)).isTrue();
        assertThat(user.getSessionEpoch()).isEqualTo(5L);
        assertThat(user.getActiveSessionExpiresAt()).isEqualTo(now.plusHours(1));
        org.mockito.Mockito.verify(repository).save(user);
    }

    @Test
    @DisplayName("No inicia una sesión para una cuenta desactivada después de autenticar")
    void shouldNotStartSessionForDisabledUser() {
        AppUser user = AppUser.builder().id(9L).username("ana").passwordHash("h")
                .role(UserRole.USER).enabled(false).sessionEpoch(4L).build();
        when(repository.findByUsernameForUpdate("ana")).thenReturn(Optional.of(user));

        assertThat(userService.startSession("ana", java.time.LocalDateTime.now())).isFalse();
        assertThat(user.getSessionEpoch()).isEqualTo(4L);
    }

    @Test
    @DisplayName("La renovación informa si la sesión sigue vigente y pertenece a la época actual")
    void shouldReturnWhetherSessionRefreshSucceeded() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 27, 12, 0);
        when(repository.extendActiveSession(9L, 5L, now, now.plusHours(1))).thenReturn(1);

        assertThat(userService.refreshSession(9L, 5L, now)).isTrue();
        verify(repository).extendActiveSession(9L, 5L, now, now.plusHours(1));
    }

    @Test
    @DisplayName("Restablecer la contraseña cambia el hash")
    void shouldResetPassword() {
        AppUser stored = AppUser.builder()
                .id(1L).username("admin").passwordHash("viejo").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(stored));

        userService.resetPassword(2L, 1L, "nuevaClave1");

        assertThat(new BCryptPasswordEncoder().matches("nuevaClave1", stored.getPasswordHash())).isTrue();
        assertThat(stored.getSessionEpoch()).isEqualTo(1L);
    }
}
