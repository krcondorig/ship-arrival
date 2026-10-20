package com.shiparrival.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackingExecutionSummary {

    private int totalProcessed;
    private int updatedCount;
    private int failedCount;
    private int alertsSentCount;
    private long executionDurationMs;
    private String errorMessage;

    @Builder.Default
    private List<String> details = new ArrayList<>();
}
