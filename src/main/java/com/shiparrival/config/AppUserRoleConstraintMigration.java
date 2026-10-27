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
 * Libera la restricción CHECK que Hibernate creó sobre {@code app_users.role}.
 *
 * <p>Hibernate 6 agrega automáticamente un {@code check (role in (...))} con los
 * valores del enum al crear la tabla. Como {@code app_users} ya existía en
 * producción, esa restricción quedó grabada con los roles de entonces y un rol
 * nuevo ({@code OPERADOR}) sería rechazado por la base antes de llegar a Java.</p>
 *
 * <p>El proyecto ya tomó esta decisión para H2 con {@link ShipmentH2Dialect}: los
 * valores de los enums se validan en Java, no en la base, para que agregar una
 * naviera o un rol no exija una migración. Aquí se aplica lo mismo a PostgreSQL.
 * La sentencia es idempotente, así que corre en cada arranque sin coste.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AppUserRoleConstraintMigration implements ApplicationRunner {

    private static final String DROP_ROLE_CHECK =
            "ALTER TABLE app_users DROP CONSTRAINT IF EXISTS app_users_role_check";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public AppUserRoleConstraintMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            return;
        }
        try {
            jdbcTemplate.execute(DROP_ROLE_CHECK);
            log.info("Restricción de roles de app_users liberada: los roles nuevos no exigen migración.");
        } catch (DataAccessException ex) {
            // No detener el arranque: sin aplicación no hay forma de corregir nada.
            log.warn("No se pudo liberar la restricción de roles de app_users: {}", ex.getMessage());
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
