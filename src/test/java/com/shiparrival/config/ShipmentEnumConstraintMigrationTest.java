package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShipmentEnumConstraintMigrationTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    @DisplayName("En PostgreSQL se liberan las restricciones de estado y naviera de shipments")
    void shouldDropShipmentEnumConstraintsOnPostgres() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        new ShipmentEnumConstraintMigration(dataSource, jdbcTemplate).run(null);

        verify(jdbcTemplate).execute(
                "ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_status_check");
        verify(jdbcTemplate).execute(
                "ALTER TABLE shipments DROP CONSTRAINT IF EXISTS shipments_carrier_check");
    }

    @Test
    @DisplayName("En H2 no se toca ninguna restricción")
    void shouldDoNothingOnH2() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        new ShipmentEnumConstraintMigration(dataSource, jdbcTemplate).run(null);

        verifyNoInteractions(jdbcTemplate);
    }
}
