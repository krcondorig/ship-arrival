package com.shiparrival.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Impide que una configuración destructiva de Hibernate se ejecute sobre la base
 * de datos real.
 *
 * <p>{@code ddl-auto=create} y {@code create-drop} eliminan y recrean las tablas
 * al arrancar. Eso es aceptable en las pruebas con H2 en memoria, pero apuntado
 * a PostgreSQL borra los embarques y su historial. Esta comprobación detiene el
 * arranque antes de tocar nada.</p>
 */
@Component
public class DatabaseSafetyGuard implements InitializingBean {

    private final String datasourceUrl;
    private final String ddlAuto;

    public DatabaseSafetyGuard(
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.jpa.hibernate.ddl-auto:}") String ddlAuto) {
        this.datasourceUrl = datasourceUrl;
        this.ddlAuto = ddlAuto;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = ddlAuto == null ? "" : ddlAuto.trim().toLowerCase(Locale.ROOT);
        boolean destructive = "create".equals(mode) || "create-drop".equals(mode);
        boolean inMemoryDatabase = datasourceUrl != null
                && datasourceUrl.trim().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");

        if (destructive && !inMemoryDatabase) {
            throw new IllegalStateException(
                    "Configuración destructiva rechazada: spring.jpa.hibernate.ddl-auto=" + ddlAuto
                            + " con la base de datos " + datasourceUrl
                            + ". Usa 'update' o 'none' fuera de las pruebas con H2.");
        }
    }
}
