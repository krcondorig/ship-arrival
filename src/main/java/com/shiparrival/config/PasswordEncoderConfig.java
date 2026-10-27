package com.shiparrival.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Algoritmo de hash para las contraseñas.
 *
 * <p>Se usa BCrypt con factor 10: es lento a propósito, de modo que probar
 * millones de contraseñas contra el hash almacenado sea inviable.</p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
