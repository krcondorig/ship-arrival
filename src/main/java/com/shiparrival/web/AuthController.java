package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.service.LoginAttemptService;
import com.shiparrival.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Acceso al dashboard.
 *
 * <p>Los mensajes de error son siempre genéricos: no se revela si el usuario
 * existe, solo que la combinación no es válida.</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final LoginAttemptService loginAttemptService;

    @GetMapping("/login")
    public String loginForm(@RequestParam(required = false) String redirect, Model model) {
        model.addAttribute("redirect", redirect);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        @RequestParam(required = false) String redirect,
                        HttpServletRequest request,
                        Model model) {

        if (loginAttemptService.isBlocked(username)) {
            model.addAttribute("errorMessage", "Demasiados intentos fallidos. Espera 15 minutos.");
            model.addAttribute("redirect", redirect);
            return "login";
        }

        Optional<AppUser> user = userService.authenticate(username, password);
        if (user.isEmpty()) {
            loginAttemptService.recordFailure(username);
            log.warn("Intento de acceso fallido para el usuario {}", username);
            model.addAttribute("errorMessage", "Usuario o contraseña incorrectos.");
            model.addAttribute("redirect", redirect);
            return "login";
        }

        loginAttemptService.recordSuccess(username);
        userService.touchLastLogin(user.get().getId());

        HttpSession session = request.getSession(true);
        session.setAttribute(AuthFilter.SESSION_USER_ID, user.get().getId());
        session.setAttribute(AuthFilter.SESSION_EPOCH, user.get().getSessionEpoch());
        // Rotar el identificador evita que una cookie capturada antes del login
        // sirva después de autenticarse.
        request.changeSessionId();

        log.info("Inicio de sesión correcto para el usuario {}", user.get().getUsername());
        return "redirect:" + safeRedirect(redirect);
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    /**
     * Solo se aceptan rutas internas. Cualquier destino absoluto, de protocolo
     * relativo o con barras invertidas se descarta para no convertir el login en
     * un trampolín.
     */
    private String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return "/";
        }
        String candidate = redirect.trim();
        if (!candidate.startsWith("/") || candidate.startsWith("//")) {
            return "/";
        }
        // Una barra invertida la normalizan algunos navegadores a "/", lo que
        // convertiría "/\evil.example" en "//evil.example".
        if (candidate.indexOf('\\') >= 0
                || candidate.indexOf(':') >= 0
                || candidate.indexOf('\n') >= 0
                || candidate.indexOf('\r') >= 0) {
            return "/";
        }
        return candidate;
    }
}
