package com.shiparrival.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Servicio en memoria para gestionar la regla de sesión única y el tiempo de vida
 * de 12 horas por cuenta.
 *
 * <p>Evita depender de modificaciones de esquema (DDL) en bases de datos gestionadas
 * con poolers de transacciones (como Supabase), eliminando errores por columnas ausentes.</p>
 */
@Service
public class ActiveSessionService {

    private final ConcurrentMap<Long, LocalDateTime> activeSessions = new ConcurrentHashMap<>();

    public boolean tryStartSession(Long userId, LocalDateTime now) {
        cleanExpired(now);
        LocalDateTime expiresAt = activeSessions.get(userId);
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return false;
        }
        activeSessions.put(userId, now.plusHours(1));
        return true;
    }

    public void endSession(Long userId) {
        if (userId != null) {
            activeSessions.remove(userId);
        }
    }

    public boolean isSessionActive(Long userId, LocalDateTime now) {
        if (userId == null) {
            return false;
        }
        cleanExpired(now);
        LocalDateTime expiresAt = activeSessions.get(userId);
        return expiresAt != null && expiresAt.isAfter(now);
    }

    private void cleanExpired(LocalDateTime now) {
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isBefore(now) || entry.getValue().isEqual(now));
    }
}
