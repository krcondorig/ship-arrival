package com.shiparrival.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestAttribute;

/**
 * Expone el usuario autenticado a todas las plantillas Thymeleaf.
 *
 * <p>Sin este asesor, {@code ${currentUser}} no resolvería aunque el filtro
 * hubiera dejado el dato en la petición: Thymeleaf busca primero en el modelo.</p>
 */
@ControllerAdvice
public class CurrentUserModelAdvice {

    @ModelAttribute("currentUser")
    public CurrentUser currentUser(
            @RequestAttribute(value = AuthFilter.CURRENT_USER_ATTRIBUTE, required = false)
            CurrentUser currentUser) {
        return currentUser;
    }
}
