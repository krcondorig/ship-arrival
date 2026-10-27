package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protege el presupuesto de conexiones de Supabase.
 *
 * <p>El pooler de Supabase en modo sesión (puerto 5432) acepta como máximo 15
 * clientes concurrentes. El dashboard desplegado y las ejecuciones desatendidas
 * comparten ese presupuesto, por lo que el pool de Hikari debe ser pequeño. Si
 * se deja el valor por defecto (10), dos procesos ya lo agotan y la aplicación
 * falla al arrancar con {@code EMAXCONNSESSION}.</p>
 */
class DatabasePoolConfigurationTest {

    private static final int SUPABASE_SESSION_POOLER_LIMIT = 15;
    private static final Pattern PLACEHOLDER_DEFAULT =
            Pattern.compile("\\$\\{[^:}]+:(\\d+)}");

    private final Properties properties = loadApplicationProperties();

    @Test
    @DisplayName("Dos procesos (dashboard + CI) deben caber en los 15 clientes del pooler")
    void shouldFitTwoInstancesWithinSessionPoolerLimit() {
        int maxPoolSize = intProperty("spring.datasource.hikari.maximum-pool-size");

        assertThat(maxPoolSize * 2)
                .as("el dashboard y una ejecución de CI comparten el pooler de Supabase")
                .isLessThan(SUPABASE_SESSION_POOLER_LIMIT);
    }

    @Test
    @DisplayName("El mínimo de conexiones ociosas no debe superar el máximo del pool")
    void minimumIdleShouldNotExceedMaximumPoolSize() {
        int maxPoolSize = intProperty("spring.datasource.hikari.maximum-pool-size");
        int minIdle = intProperty("spring.datasource.hikari.minimum-idle");

        assertThat(minIdle).isLessThanOrEqualTo(maxPoolSize);
    }

    @Test
    @DisplayName("El pool debe liberar conexiones ociosas para no retener sesiones del pooler")
    void shouldReleaseIdleConnections() {
        long idleTimeout = longProperty("spring.datasource.hikari.idle-timeout");

        assertThat(idleTimeout).isPositive();
        assertThat(idleTimeout).isLessThanOrEqualTo(300_000L);
    }

    @Test
    @DisplayName("La espera por conexión debe ser finita para fallar rápido ante el pooler saturado")
    void connectionTimeoutShouldBeBounded() {
        long connectionTimeout = longProperty("spring.datasource.hikari.connection-timeout");

        assertThat(connectionTimeout).isPositive();
        assertThat(connectionTimeout).isLessThanOrEqualTo(60_000L);
    }

    private int intProperty(String key) {
        return Integer.parseInt(resolvedValue(key));
    }

    private long longProperty(String key) {
        return Long.parseLong(resolvedValue(key));
    }

    private String resolvedValue(String key) {
        String raw = properties.getProperty(key);
        assertThat(raw)
                .as("falta la propiedad %s en application.properties", key)
                .isNotNull();
        Matcher matcher = PLACEHOLDER_DEFAULT.matcher(raw.trim());
        return matcher.matches() ? matcher.group(1) : raw.trim();
    }

    private static Properties loadApplicationProperties() {
        // src/test/resources/application.properties sombrea al de producción en el
        // classpath de pruebas, por eso se lee el archivo real del proyecto.
        Path productionProperties = Path.of("src", "main", "resources", "application.properties");
        assertThat(productionProperties)
                .as("no se encontró %s (se ejecutan las pruebas desde la raíz del proyecto)",
                        productionProperties.toAbsolutePath())
                .exists();

        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(productionProperties)) {
            loaded.load(in);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo leer " + productionProperties, ex);
        }
        return loaded;
    }
}
