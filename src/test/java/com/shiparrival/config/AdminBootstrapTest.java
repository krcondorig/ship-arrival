package com.shiparrival.config;

import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private AppUserRepository repository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        userService = mock(UserService.class);
    }

    @Test
    @DisplayName("Crea el primer administrador cuando no hay cuentas")
    void shouldCreateFirstAdminWhenDatabaseIsEmpty() {
        when(repository.count()).thenReturn(0L);

        new AdminBootstrap(repository, userService, "admin", "claveSegura1").run(null);

        verify(userService).createUser("admin", "claveSegura1", UserRole.ADMIN);
    }

    @Test
    @DisplayName("No toca nada si ya existen cuentas")
    void shouldDoNothingWhenUsersExist() {
        when(repository.count()).thenReturn(3L);

        new AdminBootstrap(repository, userService, "admin", "claveSegura1").run(null);

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("Sin credenciales configuradas no inventa una cuenta")
    void shouldSkipWhenCredentialsMissing() {
        when(repository.count()).thenReturn(0L);

        new AdminBootstrap(repository, userService, "", "").run(null);
        new AdminBootstrap(repository, userService, "  ", null).run(null);

        verify(userService, never()).createUser(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
