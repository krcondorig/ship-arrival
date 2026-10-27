package com.shiparrival.web;

import com.shiparrival.domain.AppUser;
import com.shiparrival.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Exige sesión válida para acceder al dashboard.
 *
 * <p>La sesión solo guarda el identificador del usuario. En cada petición se
 * vuelve a leer la cuenta, de modo que desactivar a alguien surte efecto de
 * inmediato y no cuando expire su sesión.</p>
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String SESSION_USER_ID = "auth.userId";
    public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

    private static final List<String> PUBLIC_PATHS =
            List.of("/login", "/css/", "/js/", "/favicon.ico", "/error");

    private final AppUserRepository repository;
    private final boolean enabled;

    public AuthFilter(AppUserRepository repository, boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!enabled || isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(SESSION_USER_ID);

        if (userId instanceof Long id) {
            Optional<AppUser> user = repository.findById(id);
            if (user.isPresent() && user.get().isEnabled()) {
                AppUser found = user.get();
                request.setAttribute(CURRENT_USER_ATTRIBUTE,
                        new CurrentUser(found.getId(), found.getUsername(), found.getRole()));
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(request.getContextPath() + "/login");
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
