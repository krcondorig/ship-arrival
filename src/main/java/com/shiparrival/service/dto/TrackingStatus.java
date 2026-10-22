package com.shiparrival.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TrackingStatus {

    private boolean running;
    private int total;
    private int processed;
    private int updated;
    private int failed;
    private int alertsSent;
    private String currentTrackingNumber;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private long durationMs;
    private String message;

    @Builder.Default
    private List<String> details = new ArrayList<>();

    public static TrackingStatus idle() {
        return TrackingStatus.builder()
                .running(false)
                .message("Sin monitoreo en curso")
                .build();
    }
}
