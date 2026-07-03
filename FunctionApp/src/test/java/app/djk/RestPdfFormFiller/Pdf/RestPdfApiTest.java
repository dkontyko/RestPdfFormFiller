package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormDataException;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestPdfApiTest {

    @Test
    void isXfaFormReturnsTrueForSampleDa4187() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();

        assertTrue(RestPdfApi.isXfaForm(samplePdfBytes));
    }

    @Test
    void getXfaDatasetNodeAsStringReturnsExpectedDatasetContent() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();

        final var dataXml = RestPdfApi.getXfaDatasetNodeAsString(samplePdfBytes);

        assertTrue(dataXml.contains("<xfa:datasets"));
        assertTrue(dataXml.contains("<xfa:data>"));
        assertTrue(dataXml.contains("<SSN>123-45-6789</SSN>"));
    }

    @Test
    void fillXfaFormRejectsNonXfaPdf() throws Exception {
        final var nonXfaPdfBytes = createSimpleNonXfaPdf();
        final var jsonFormData = "{\"data\":{\"field\":\"value\"}}";

        assertFalse(RestPdfApi.isXfaForm(nonXfaPdfBytes));
        assertThrows(InvalidXfaFormException.class, () -> RestPdfApi.fillXfaForm(nonXfaPdfBytes, jsonFormData));
    }

    @Test
    void fillXfaFormRejectsInvalidJsonFormDataShape() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var invalidJsonFormData = "{\"notData\":{\"field\":\"value\"}}";

        assertThrows(InvalidXfaFormDataException.class,
                () -> RestPdfApi.fillXfaForm(samplePdfBytes, invalidJsonFormData));
    }

    private static byte[] readSampleDa4187Pdf() throws Exception {
        final var moduleRoot = Path.of("").toAbsolutePath();
        final var sampleInRepoRoot = moduleRoot.resolve("../resources/DA4187/A4187.pdf").normalize();
        final var sampleInModule = moduleRoot.resolve("resources/DA4187/A4187.pdf").normalize();

        if (Files.exists(sampleInRepoRoot)) {
            return Files.readAllBytes(sampleInRepoRoot);
        }
        if (Files.exists(sampleInModule)) {
            return Files.readAllBytes(sampleInModule);
        }

        throw new IllegalStateException("Could not locate sample file A4187.pdf for tests.");
    }

    private static byte[] createSimpleNonXfaPdf() throws Exception {
        try (final var output = new ByteArrayOutputStream()) {
            final var document = new Document();
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph("Simple PDF"));
            document.close();
            return output.toByteArray();
        }
    }
}
