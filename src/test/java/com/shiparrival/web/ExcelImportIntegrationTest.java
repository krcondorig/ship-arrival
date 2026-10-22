package com.shiparrival.web;

import com.shiparrival.domain.Carrier;
import com.shiparrival.repository.ShipmentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tracking.request.delay-ms=0",
        "spring.datasource.url=jdbc:h2:mem:excelimport;DB_CLOSE_DELAY=-1"
})
class ExcelImportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Test
    @DisplayName("La carga Excel asigna la naviera de cada fila y usa la predeterminada si falta")
    void shouldImportMixedCarriersFromExcel() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guias-mixtas.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                createWorkbook()
        );

        mockMvc.perform(multipart("/shipments/upload-excel")
                        .file(file)
                        .param("carrier", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("successMessage"))
                .andExpect(flash().attributeExists("warningMessage"));

        assertThat(shipmentRepository.findByTrackingNumber("TESTMIX0001").orElseThrow().getCarrier())
                .isEqualTo(Carrier.ONE);
        assertThat(shipmentRepository.findByTrackingNumber("TESTMIX0002").orElseThrow().getCarrier())
                .isEqualTo(Carrier.MSC);
        assertThat(shipmentRepository.findByTrackingNumber("TESTMIX0003").orElseThrow().getCarrier())
                .isEqualTo(Carrier.ONE);
        assertThat(shipmentRepository.findByTrackingNumber("TESTMIX0004")).isEmpty();
    }

    private byte[] createWorkbook() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Guias");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("GUIA");
            header.createCell(1).setCellValue("NAVIERA");

            addRow(sheet, 1, "TESTMIX0001", "ONE");
            addRow(sheet, 2, "TESTMIX0002", "msc");
            addRow(sheet, 3, "TESTMIX0003", "");
            addRow(sheet, 4, "TESTMIX0004", "MAERSK");

            workbook.write(out);
        }
        return out.toByteArray();
    }

    private void addRow(Sheet sheet, int rowIndex, String trackingNumber, String carrier) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(trackingNumber);
        row.createCell(1).setCellValue(carrier);
    }
}
