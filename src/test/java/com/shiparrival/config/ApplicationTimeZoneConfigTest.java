package com.shiparrival.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * La aplicación registra fechas con {@link LocalDateTime}, que no lleva zona
 * horaria: toma la hora del reloj del sistema donde corre el proceso.
 *
 * <p>El runner de GitHub Actions está en UTC, mientras que el equipo local está
 * en America/Lima. Sin una zona explícita, el mismo monitoreo guardaba
 * "último chequeo" con 5 horas de diferencia según dónde se ejecutara. Estos
 * tests garantizan que la zona se fije siempre, sin depender del anfitrión.</p>
 */
class ApplicationTimeZoneConfigTest {

    private TimeZone originalTimeZone;

    @BeforeEach
    void rememberTimeZone() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    @DisplayName("Aplica la zona configurada aunque el anfitrión esté en UTC")
    void shouldApplyConfiguredZoneRegardlessOfHostZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ApplicationTimeZoneConfig.apply("America/Lima");

        assertThat(TimeZone.getDefault().getID()).isEqualTo("America/Lima");
    }

    @Test
    @DisplayName("LocalDateTime.now() coincide con la hora local configurada, no con la del anfitrión")
    void localDateTimeShouldMatchConfiguredZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ApplicationTimeZoneConfig.apply("America/Lima");

        LocalDateTime expected = ZonedDateTime.now(ZoneId.of("America/Lima")).toLocalDateTime();

        assertThat(LocalDateTime.now()).isCloseTo(expected, within(2, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("La zona por defecto del proyecto es America/Lima")
    void defaultZoneShouldBeLima() {
        assertThat(ApplicationTimeZoneConfig.DEFAULT_ZONE).isEqualTo("America/Lima");
    }

    @Test
    @DisplayName("Una zona inválida falla al arrancar en lugar de guardar fechas incorrectas")
    void shouldRejectInvalidZone() {
        assertThatThrownBy(() -> ApplicationTimeZoneConfig.apply("No/Existe"))
                .isInstanceOf(DateTimeException.class);
    }
}
