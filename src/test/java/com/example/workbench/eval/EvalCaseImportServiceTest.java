package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class EvalCaseImportServiceTest {

    @Test
    void rejectsZipSlipEntryBeforeParsing() {
        EvalCaseImportService service = service(10, 1000, 2000, 100, 10);

        assertThatThrownBy(() -> service.importCases(null, file(zip("../xl/worksheets/sheet1.xml", "x"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ZIP");
    }

    @Test
    void rejectsEntryWhenDecompressedBytesExceedLimit() {
        EvalCaseImportService service = service(10, 3, 1000, 100, 10);

        assertThatThrownBy(() -> service.importCases(null, file(zip("xl/sharedStrings.xml", "1234"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("单个条目");
    }

    @Test
    void rejectsTotalDecompressedBytesWhenEntriesIndividuallyFit() {
        EvalCaseImportService service = service(10, 10, 3, 100, 10);

        assertThatThrownBy(() -> service.importCases(null, file(zip(new String[][] {
                {"xl/sharedStrings.xml", "12"}, {"xl/styles.xml", "34"}
        }))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("总大小");
    }

    @Test
    void rejectsTooManyRowsAndCases() {
        String sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>question</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\"><v>one</v></c></row>"
                + "<row r=\"3\"><c r=\"A3\"><v>two</v></c></row></sheetData></worksheet>";
        EvalCaseImportService service = service(10, 1000, 10000, 2, 1);

        assertThatThrownBy(() -> service.importCases(null, file(zip("xl/worksheets/sheet1.xml", sheet))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("行数");
    }

    @Test
    void rejectsTooManyImportedCases() {
        String sheet = "<worksheet><sheetData><row><c r=\"A1\"><v>question</v></c></row>"
                + "<row><c r=\"A2\"><v>one</v></c></row><row><c r=\"A3\"><v>two</v></c></row></sheetData></worksheet>";
        EvalCaseImportService service = service(10, 1000, 10000, 100, 1);

        assertThatThrownBy(() -> service.importCases(null, file(zip("xl/worksheets/sheet1.xml", sheet))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("题数");
    }

    private EvalCaseImportService service(int entries, long entryBytes, long totalBytes, int rows, int cases) {
        return new EvalCaseImportService(mock(EvalCaseService.class), mock(EvalImportStorage.class), new ObjectMapper(),
                entries, entryBytes, totalBytes, rows, cases);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "cases.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private byte[] zip(String name, String content) {
        return zip(new String[][] {{name, content}});
    }

    private byte[] zip(String[][] entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (String[] entry : entries) {
                    zip.putNextEntry(new ZipEntry(entry[0]));
                    zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
