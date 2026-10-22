package com.shiparrival.service;

import com.shiparrival.service.dto.ExcelImportResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelImportServiceTest {

    private final ExcelImportService excelImportService = new ExcelImportService();

    @Test
    @DisplayName("Debe extraer guías válidas ignorando espacios, celdas vacías y encabezados")
    void shouldExtractValidTrackingNumbersFromExcel() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Guias");

            // Fila 0: Encabezados
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("GUIA / BL");
            headerRow.createCell(1).setCellValue("OBSERVACIONES");

            // Fila 1: Guía válida con espacios
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("  TESTBL900001  ");
            row1.createCell(1).setCellValue("Embarque 1");

            // Fila 2: Guía válida normal
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("TESTBL900002");

            // Fila 3: Celda vacía
            Row row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("");

            // Fila 4: Celda con solo espacios
            Row row4 = sheet.createRow(4);
            row4.createCell(0).setCellValue("   ");

            // Fila 5: Guía válida adicional
            Row row5 = sheet.createRow(5);
            row5.createCell(0).setCellValue("TESTBL900003");

            workbook.write(out);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        ExcelImportResult result = excelImportService.extractTrackingNumbers(in);

        assertThat(result).isNotNull();
        assertThat(result.getValidTrackingNumbers())
                .containsExactly("TESTBL900001", "TESTBL900002", "TESTBL900003");
        assertThat(result.getTotalRowsProcessed()).isGreaterThanOrEqualTo(5);
    }
}
