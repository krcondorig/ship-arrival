package com.shiparrival.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Fija la zona horaria de la aplicación al arrancar.
 *
 * <p>Las entidades guardan {@link java.time.LocalDateTime}, que no contiene
 * información de zona: su valor sale del reloj del sistema operativo. Eso hacía
 * que el mismo monitoreo registrara "último chequeo" con horas distintas según
 * dónde corriera: en el equipo local (America/Lima) o en el runner de GitHub
 * Actions (UTC), que está 5 horas adelantado.</p>
 *
 * <p>Al fijar la zona de forma explícita, el resultado es el mismo en local, en
 * CI y en Render, sin depender de la configuración del anfitrión.</p>
 */
@Slf4j
@Configuration
public class ApplicationTimeZoneConfig {

    public static final String DEFAULT_ZONE = "America/Lima";

    private final String timeZoneId;

    public ApplicationTimeZoneConfig(
            @Value("${app.timezone:" + DEFAULT_ZONE + "}") String timeZoneId) {
        this.timeZoneId = timeZoneId;
    }

    @PostConstruct
    public void configureDefaultTimeZone() {
        apply(timeZoneId);
    }

    /**
     * Establece la zona horaria por defecto del proceso.
     *
     * @throws java.time.DateTimeException si el identificador de zona no existe,
     *                                     para no guardar fechas silenciosamente incorrectas
     */
    public static void apply(String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        System.setProperty("user.timezone", zone.getId());
        log.info("Zona horaria de la aplicación establecida en {}", zone.getId());
    }
}
