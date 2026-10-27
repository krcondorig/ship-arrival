package com.shiparrival.web;

import com.shiparrival.domain.UserRole;

/**
 * Usuario autenticado en la petición actual.
 *
 * <p>Se expone como atributo de petición con el nombre
 * {@link AuthFilter#CURRENT_USER_ATTRIBUTE} y como variable de modelo para las
 * plantillas Thymeleaf.</p>
 */
public record CurrentUser(Long id, String username, UserRole role) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /**
     * Trabajo del día a día: registrar, importar, eliminar guías y disparar el
     * monitoreo. Lo comparten el administrador y el operador; consultar la lista
     * de cuentas queda solo en manos del administrador.
     */
    public boolean canOperate() {
        return role == UserRole.ADMIN || role == UserRole.OPERADOR;
    }
}
