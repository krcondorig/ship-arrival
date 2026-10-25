package com.shiparrival.config;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration
@Profile("prod")
public class SupabaseDataSourceConfig {

    private final Environment env;

    public SupabaseDataSourceConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public DataSource dataSource() {
        String url = env.getProperty("spring.datasource.url");
        if (url != null && !url.trim().isEmpty()) {
            url = url.trim();
            // Supabase / cloud providers often provide URLs starting with postgresql:// or postgres:// without the jdbc: prefix
            if (!url.startsWith("jdbc:")) {
                if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
                    url = "jdbc:" + url;
                }
            }
            // Supabase strictly requires SSL for external connections
            if (!url.contains("sslmode=")) {
                url += (url.contains("?") ? "&" : "?") + "sslmode=require";
            }
        }

        String driver = env.getProperty("spring.datasource.driverClassName", "org.postgresql.Driver");
        String username = env.getProperty("spring.datasource.username", "postgres");
        String password = env.getProperty("spring.datasource.password", "");

        return DataSourceBuilder.create()
                .driverClassName(driver)
                .url(url)
                .username(username)
                .password(password)
                .build();
    }
}
