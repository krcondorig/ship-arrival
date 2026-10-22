package com.shiparrival.service;

import com.shiparrival.domain.Carrier;
import com.shiparrival.service.dto.ExcelImportResult;
import com.shiparrival.service.dto.ExcelShipmentRow;
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
import static org.assertj.core.api.Assertions.tuple;

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
        assertThat(result.getValidRows())
                .extracting(ExcelShipmentRow::getTrackingNumber)
                .containsExactly("TESTBL900001", "TESTBL900002", "TESTBL900003");
        assertThat(result.getValidRows())
                .extracting(ExcelShipmentRow::getCarrier)
                .containsOnlyNulls();
        assertThat(result.getTotalRowsProcessed()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Debe leer una columna NAVIERA opcional y rechazar navieras inválidas")
    void shouldExtractOptionalCarrierColumn() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Guias");

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("GUIA");
            headerRow.createCell(1).setCellValue("NAVIERA");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("TESTBL900001");
            row1.createCell(1).setCellValue("ONE");

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("TESTBL900002");
            row2.createCell(1).setCellValue("msc");

            Row row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("TESTBL900003");
            row3.createCell(1).setCellValue("");

            Row row4 = sheet.createRow(4);
            row4.createCell(0).setCellValue("TESTBL900004");
            row4.createCell(1).setCellValue("MAERSK");

            Row row5 = sheet.createRow(5);
            row5.createCell(0).setCellValue("TESTBL900005");
            row5.createCell(1).setCellValue("UNKNOWN");

            workbook.write(out);
        }

        ExcelImportResult result = excelImportService.extractTrackingNumbers(
                new ByteArrayInputStream(out.toByteArray()));

        assertThat(result.getValidRows())
                .extracting(ExcelShipmentRow::getTrackingNumber, ExcelShipmentRow::getCarrier)
                .containsExactly(
                        tuple("TESTBL900001", Carrier.ONE),
                        tuple("TESTBL900002", Carrier.MSC),
                        tuple("TESTBL900003", null),
                        tuple("TESTBL900005", Carrier.UNKNOWN));
        assertThat(result.getInvalidRows())
                .containsExactly("Fila 5: naviera inválida 'MAERSK'");
    }
}
