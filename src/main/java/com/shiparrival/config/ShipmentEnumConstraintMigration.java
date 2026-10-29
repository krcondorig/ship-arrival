package com.shiparrival.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Libera las restricciones CHECK que Hibernate creó sobre {@code shipments.status}
 * y {@code shipments.carrier}.
 *
 * <p>Hibernate 6 agrega automáticamente un {@code check (status in (...))} con los
 * valores del enum al crear la tabla. Como {@code shipments} ya existía en
 * producción, esa restricción quedó grabada con los estados de entonces y un
 * estado nuevo ({@code MANUAL_CHECK}) sería rechazado por la base antes de
 * llegar a Java.</p>
 *
 * <p>El proyecto ya tomó esta decisión para H2 con {@link ShipmentH2Dialect}: los
 * valores de los enums se validan en Java, no en la base, para que agregar una
 * naviera o un estado no exija una migración. Aquí se aplica lo mismo a
 * PostgreSQL. Las sentencias son idempotentes, así que corren en cada arranque
 * sin coste.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShipmentEnumConstraintMigration implements ApplicationRunner {

    private static final String DROP_STATUS_CHECK =
            "ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_status_check";
    private static final String DROP_CARRIER_CHECK =
            "ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_carrier_check";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ShipmentEnumConstraintMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            return;
        }
        try {
            jdbcTemplate.execute(DROP_STATUS_CHECK);
            jdbcTemplate.execute(DROP_CARRIER_CHECK);
            log.info("Restricciones de estado y naviera de shipments liberadas.");
        } catch (DataAccessException ex) {
            // No detener el arranque: sin aplicación no hay forma de corregir nada.
            log.warn("No se pudieron liberar las restricciones de shipments: {}", ex.getMessage());
        }
    }

    private boolean isPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException ex) {
            log.warn("No se pudo identificar el motor de base de datos: {}", ex.getMessage());
            return false;
        }
    }
}
