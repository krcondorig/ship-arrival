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
public class BatchImportSummary {

    private int totalProcessed;
    private int addedCount;
    private int duplicateCount;

    @Builder.Default
    private List<String> addedTrackingNumbers = new ArrayList<>();

    @Builder.Default
    private List<String> duplicateTrackingNumbers = new ArrayList<>();

    @Builder.Default
    private List<String> errorMessages = new ArrayList<>();
}
