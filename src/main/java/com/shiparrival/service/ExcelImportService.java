package com.shiparrival.service;

import com.shiparrival.service.dto.ExcelImportResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ExcelImportService {

    private final DataFormatter dataFormatter = new DataFormatter();

    public ExcelImportResult extractTrackingNumbers(InputStream inputStream) {
        List<String> validList = new ArrayList<>();
        List<String> invalidList = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return ExcelImportResult.builder().totalRowsProcessed(0).build();
            }

            Sheet sheet = workbook.getSheetAt(0);
            int trackingColIndex = 0;
            int startRow = 0;

            // Detectar columna de encabezados
            Row firstRow = sheet.getRow(0);
            if (firstRow != null) {
                for (Cell cell : firstRow) {
                    String headerText = dataFormatter.formatCellValue(cell).trim().toUpperCase();
                    if (headerText.contains("GUIA") || headerText.contains("BL") ||
                        headerText.contains("TRACKING") || headerText.contains("EMBARQUE")) {
                        trackingColIndex = cell.getColumnIndex();
                        startRow = 1;
                        break;
                    }
                }
            }

            int lastRowNum = sheet.getLastRowNum();
            for (int r = startRow; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                totalRows++;

                Cell cell = row.getCell(trackingColIndex);
                if (cell == null) {
                    continue;
                }

                String val = dataFormatter.formatCellValue(cell).trim();
                if (val.isEmpty()) {
                    continue;
                }

                // Descartar si coincide con el texto del encabezado repetido
                String upper = val.toUpperCase();
                if (upper.equals("GUIA") || upper.equals("BL") || upper.equals("TRACKING")) {
                    continue;
                }

                // Criterio de validación básica: al menos 5 caracteres alfanuméricos
                if (val.length() >= 5 && val.matches("^[a-zA-Z0-9_-]+$")) {
                    validList.add(val);
                } else {
                    invalidList.add("Fila " + (r + 1) + ": valor inválido '" + val + "'");
                }
            }

        } catch (Exception ex) {
            log.error("Error al procesar archivo Excel: {}", ex.getMessage(), ex);
            throw new IllegalArgumentException("No se pudo leer el archivo Excel: " + ex.getMessage(), ex);
        }

        return ExcelImportResult.builder()
                .validTrackingNumbers(validList)
                .invalidRows(invalidList)
                .totalRowsProcessed(totalRows)
                .build();
    }
}
