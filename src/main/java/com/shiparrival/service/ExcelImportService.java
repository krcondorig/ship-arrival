package com.shiparrival.service;

import com.shiparrival.domain.Carrier;
import com.shiparrival.service.dto.ExcelImportResult;
import com.shiparrival.service.dto.ExcelShipmentRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class ExcelImportService {

    private final DataFormatter dataFormatter = new DataFormatter();

    public ExcelImportResult extractTrackingNumbers(InputStream inputStream) {
        List<ExcelShipmentRow> validList = new ArrayList<>();
        List<String> invalidList = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return ExcelImportResult.builder().totalRowsProcessed(0).build();
            }

            Sheet sheet = workbook.getSheetAt(0);
            int trackingColIndex = 0;
            int carrierColIndex = -1;
            int startRow = 0;

            // Detectar columnas de encabezados
            Row firstRow = sheet.getRow(0);
            if (firstRow != null) {
                boolean trackingHeaderFound = false;
                for (Cell cell : firstRow) {
                    String headerText = dataFormatter.formatCellValue(cell).trim().toUpperCase(Locale.ROOT);
                    if (!trackingHeaderFound && isTrackingHeader(headerText)) {
                        trackingColIndex = cell.getColumnIndex();
                        trackingHeaderFound = true;
                    }
                    if (carrierColIndex < 0 && isCarrierHeader(headerText)) {
                        carrierColIndex = cell.getColumnIndex();
                    }
                }
                if (trackingHeaderFound || carrierColIndex >= 0) {
                    startRow = 1;
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
                String upper = val.toUpperCase(Locale.ROOT);
                if (upper.equals("GUIA") || upper.equals("BL") || upper.equals("TRACKING")) {
                    continue;
                }

                // Criterio de validación básica: al menos 5 caracteres alfanuméricos
                if (val.length() < 5 || !val.matches("^[a-zA-Z0-9_-]+$")) {
                    invalidList.add("Fila " + (r + 1) + ": valor inválido '" + val + "'");
                    continue;
                }

                Carrier carrier = null;
                if (carrierColIndex >= 0) {
                    Cell carrierCell = row.getCell(carrierColIndex);
                    String carrierValue = carrierCell != null
                            ? dataFormatter.formatCellValue(carrierCell).trim()
                            : "";
                    if (!carrierValue.isEmpty()) {
                        try {
                            carrier = Carrier.valueOf(carrierValue.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ex) {
                            invalidList.add("Fila " + (r + 1) + ": naviera inválida '" + carrierValue + "'");
                            continue;
                        }
                    }
                }

                validList.add(ExcelShipmentRow.builder()
                        .trackingNumber(val)
                        .carrier(carrier)
                        .build());
            }

        } catch (Exception ex) {
            log.error("Error al procesar archivo Excel: {}", ex.getMessage(), ex);
            throw new IllegalArgumentException("No se pudo leer el archivo Excel: " + ex.getMessage(), ex);
        }

        return ExcelImportResult.builder()
                .validRows(validList)
                .invalidRows(invalidList)
                .totalRowsProcessed(totalRows)
                .build();
    }

    private boolean isTrackingHeader(String headerText) {
        return headerText.contains("GUIA")
                || headerText.contains("BL")
                || headerText.contains("B/L")
                || headerText.contains("TRACKING")
                || headerText.contains("EMBARQUE");
    }

    private boolean isCarrierHeader(String headerText) {
        return headerText.contains("NAVIERA") || headerText.contains("CARRIER");
    }
}
