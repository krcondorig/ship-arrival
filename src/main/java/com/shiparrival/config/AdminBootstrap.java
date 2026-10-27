package com.shiparrival.config;

import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crea la primera cuenta administradora cuando la base está vacía.
 *
 * <p>Es la única forma de entrar la primera vez: no hay registro público ni
 * recuperación por correo. Si las credenciales no están configuradas, el sistema
 * arranca pero avisa de que nadie podrá acceder.</p>
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AppUserRepository repository;
    private final UserService userService;
    private final String username;
    private final String password;

    public AdminBootstrap(AppUserRepository repository,
                          UserService userService,
                          @Value("${app.auth.admin.username:}") String username,
                          @Value("${app.auth.admin.password:}") String password) {
        this.repository = repository;
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        if (isBlank(username) || isBlank(password)) {
            log.warn("No hay cuentas registradas y faltan ADMIN_USERNAME/ADMIN_PASSWORD: "
                    + "el dashboard quedará sin acceso hasta que se configuren.");
            return;
        }

        try {
            userService.createUser(username, password, UserRole.ADMIN);
            log.info("Cuenta administradora inicial creada: {}", username.trim().toLowerCase());
        } catch (RuntimeException ex) {
            // Un ADMIN_PASSWORD demasiado corto o un ADMIN_USERNAME inválido no
            // deben impedir que el dashboard arranque: sin app no hay ni siquiera
            // forma de corregir la variable.
            log.error("No se pudo crear la cuenta administradora inicial: {}", ex.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
