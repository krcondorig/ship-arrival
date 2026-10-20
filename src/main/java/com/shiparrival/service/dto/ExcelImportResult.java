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
public class ExcelImportResult {

    @Builder.Default
    private List<String> validTrackingNumbers = new ArrayList<>();

    @Builder.Default
    private List<String> invalidRows = new ArrayList<>();

    private int totalRowsProcessed;
}
