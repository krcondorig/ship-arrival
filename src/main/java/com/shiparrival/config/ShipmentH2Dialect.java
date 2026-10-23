package com.shiparrival.config;

import org.hibernate.dialect.H2Dialect;

/**
 * Dialecto H2 que desactiva las restricciones CHECK que Hibernate genera
 * automáticamente para los valores de los enums.
 *
 * <p>Sin esto, una base de datos nueva quedaría limitada a las navieras
 * existentes en el momento de su creación y habría que migrar la restricción
 * cada vez que se agregue una nueva. La validación de los enums se sigue
 * haciendo en Java.</p>
 */
public class ShipmentH2Dialect extends H2Dialect {

    @Override
    public String getCheckCondition(String columnName, String[] values) {
        return null;
    }

    @Override
    public String getCheckCondition(String columnName, Class<? extends Enum<?>> enumClass) {
        return null;
    }

    @Override
    public String getCheckCondition(String columnName, long min, long max) {
        return null;
    }

    @Override
    public String getCheckCondition(String columnName, long[] values) {
        return null;
    }

    @Override
    public String getCheckCondition(String columnName, Long[] values) {
        return null;
    }
}
