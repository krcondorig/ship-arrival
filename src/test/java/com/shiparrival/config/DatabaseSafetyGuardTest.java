package com.shiparrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSafetyGuardTest {

    @Test
    @DisplayName("Bloquea el arranque si create-drop apunta a una base de datos real")
    void shouldBlockCreateDropAgainstRealDatabase() {
        DatabaseSafetyGuard guard = new DatabaseSafetyGuard(
                "jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres", "create-drop");

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create-drop");
    }

    @Test
    @DisplayName("Bloquea también la variante create contra una base de datos real")
    void shouldBlockCreateAgainstRealDatabase() {
        DatabaseSafetyGuard guard = new DatabaseSafetyGuard(
                "jdbc:postgresql://localhost:5432/shiparrival", "create");

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Permite create-drop cuando la base es H2 en memoria (pruebas)")
    void shouldAllowCreateDropOnH2() {
        DatabaseSafetyGuard guard = new DatabaseSafetyGuard("jdbc:h2:mem:testdb", "create-drop");

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Permite update contra PostgreSQL (producción)")
    void shouldAllowUpdateOnPostgres() {
        DatabaseSafetyGuard guard = new DatabaseSafetyGuard(
                "jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres", "update");

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Permite ddl-auto desactivado")
    void shouldAllowNone() {
        DatabaseSafetyGuard guard = new DatabaseSafetyGuard(
                "jdbc:postgresql://localhost:5432/shiparrival", "none");

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }
}
