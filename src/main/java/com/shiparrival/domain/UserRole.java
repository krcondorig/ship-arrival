package com.shiparrival.domain;

/**
 * Rol de acceso al dashboard.
 *
 * <p>{@code ADMIN} administra las cuentas además de operar el sistema;
 * {@code OPERADOR} registra, importa, elimina guías y dispara el monitoreo, pero
 * no toca las cuentas; {@code USER} solo consulta la información de los
 * embarques.</p>
 */
public enum UserRole {
    ADMIN,
    OPERADOR,
    USER
}
