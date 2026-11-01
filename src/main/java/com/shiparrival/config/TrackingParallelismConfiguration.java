package com.shiparrival.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tracking.parallel")
public record TrackingParallelismConfiguration(boolean enabled) {
}
