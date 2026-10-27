package com.shiparrival.service;

import com.shiparrival.domain.AppUser;
import com.shiparrival.domain.UserRole;
import com.shiparrival.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alta, autenticación y administración de cuentas.
 *
 * <p>Dos reglas protegen al sistema de quedarse sin quien lo administre: nadie
 * puede desactivar, eliminar o degradar su propia cuenta, y no se puede dejar el
 * sistema sin al menos un administrador activo.</p>
 */
@Slf4j
@Service
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{3,32}");

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(AppUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, UserRole role) {
        String normalized = normalizeUsername(username);
        validateUsername(normalized);
        validatePassword(rawPassword);

        if (repository.existsByUsernameIgnoreCase(normalized)) {
            throw new IllegalArgumentException("El usuario " + normalized + " ya existe.");
        }

        return repository.save(AppUser.builder()
                .username(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return Optional.empty();
        }
        return repository.findByUsernameIgnoreCase(normalizeUsername(username))
                .filter(AppUser::isEnabled)
                .filter(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()));
    }

    @Transactional
    public void resetPassword(Long actingUserId, Long targetId, String rawPassword) {
        validatePassword(rawPassword);
        AppUser target = require(targetId);
        target.setPasswordHash(passwordEncoder.encode(rawPassword));
        target.setSessionEpoch(target.getSessionEpoch() + 1);
        repository.save(target);
    }

    @Transactional
    public void setEnabled(Long actingUserId, Long targetId, boolean enabled) {
        if (!enabled && Objects.equals(actingUserId, targetId)) {
            throw new IllegalStateException("No puedes desactivar tu propia cuenta.");
        }
        AppUser target = require(targetId);
        if (!enabled) {
            guardLastAdmin(target);
        }
        target.setEnabled(enabled);
        repository.save(target);
    }

    @Transactional
    public void changeRole(Long actingUserId, Long targetId, UserRole role) {
        if (role != UserRole.ADMIN && Objects.equals(actingUserId, targetId)) {
            throw new IllegalStateException("No puedes quitarte el rol de administrador.");
        }
        AppUser target = require(targetId);
        if (role != UserRole.ADMIN) {
            guardLastAdmin(target);
        }
        target.setRole(role);
        repository.save(target);
    }

    @Transactional
    public void deleteUser(Long actingUserId, Long targetId) {
        if (Objects.equals(actingUserId, targetId)) {
            throw new IllegalStateException("No puedes eliminar tu propia cuenta.");
        }
        AppUser target = require(targetId);
        guardLastAdmin(target);
        repository.delete(target);
    }

    @Transactional(readOnly = true)
    public List<AppUser> findAll() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "username"));
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public void touchLastLogin(Long id) {
        repository.findById(id).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            repository.save(user);
        });
    }

    String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private void validateUsername(String normalized) {
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "El usuario debe tener entre 3 y 32 caracteres y usar solo minúsculas, "
                            + "números, punto, guion o guion bajo.");
        }
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres.");
        }
    }

    private AppUser require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El usuario no existe."));
    }

    /**
     * Impide dejar el sistema sin administradores activos.
     */
    private void guardLastAdmin(AppUser target) {
        if (target.getRole() == UserRole.ADMIN
                && target.isEnabled()
                && repository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new IllegalStateException(
                    "No puedes dejar el sistema sin al menos un administrador activo.");
        }
    }
}
