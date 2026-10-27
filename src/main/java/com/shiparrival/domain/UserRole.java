package com.shiparrival.domain;

/**
 * Rol de acceso al dashboard.
 *
 * <p>{@code ADMIN} administra usuarios y dispara el monitoreo; {@code USER} solo
 * consulta la información de los embarques.</p>
 */
public enum UserRole {
    ADMIN,
    USER
}
