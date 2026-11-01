package com.shiparrival.config;

import com.shiparrival.repository.AppUserRepository;
import com.shiparrival.service.UserService;
import com.shiparrival.web.AuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registra el filtro de sesión antes que cualquier otro filtro de la aplicación.
 *
 * <p>El filtro se construye aquí y no se anota como componente: así no entra en
 * el recorte de {@code @WebMvcTest}, que sí escanea filtros y arrastraría el
 * repositorio de usuarios a pruebas que no lo necesitan.</p>
 */
@Configuration
public class AuthWebConfig {

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilterRegistration(
            AppUserRepository appUserRepository,
            UserService userService,
            @Value("${app.auth.enabled:true}") boolean enabled) {

        FilterRegistrationBean<AuthFilter> registration =
                new FilterRegistrationBean<>(new AuthFilter(appUserRepository, userService, enabled));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
