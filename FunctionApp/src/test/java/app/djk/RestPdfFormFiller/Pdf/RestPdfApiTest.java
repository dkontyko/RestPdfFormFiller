package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormDataException;
import app.djk.RestPdfFormFiller.projectExceptions.WriteConflictException;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void fillXfaFormPatchOverwriteReplacesProvidedAndPreservesUntouchedFields() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"},"
                + "\"Page2\":{\"ORG_C\":\"NEWORG\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.OVERWRITE);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        // Provided value replaced the existing one.
        assertTrue(resultXml.contains("<SSN>999-99-9999</SSN>"));
        assertFalse(resultXml.contains("<SSN>123-45-6789</SSN>"));
        // Previously empty target was filled.
        assertTrue(resultXml.contains("<ORG_C>NEWORG</ORG_C>"));
        // A field not present in the request kept its existing value.
        assertTrue(resultXml.contains("<EFFECITIVE>9988</EFFECITIVE>"));
    }

    @Test
    void fillXfaFormPatchIfEmptyOnlyWritesIntoEmptyTargets() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"},"
                + "\"Page2\":{\"ORG_C\":\"NEWORG\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.IF_EMPTY);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        // Non-empty existing target was left unchanged.
        assertTrue(resultXml.contains("<SSN>123-45-6789</SSN>"));
        assertFalse(resultXml.contains("<SSN>999-99-9999</SSN>"));
        // Empty target was filled.
        assertTrue(resultXml.contains("<ORG_C>NEWORG</ORG_C>"));
    }

    @Test
    void fillXfaFormPatchFailOnConflictThrowsWhenValueDiffersFromNonEmptyTarget() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"}}}}";

        final var conflict = assertThrows(WriteConflictException.class,
                () -> RestPdfApi.fillXfaForm(samplePdfBytes, formData, WriteMode.PATCH, PatchMode.FAIL_ON_CONFLICT));
        assertEquals("Write conflict at field 'form1/Page1/SSN': target already has a different value.",
                conflict.getMessage());
    }

    @Test
    void fillXfaFormPatchFailOnConflictSucceedsWhenTargetIsEmpty() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page2\":{\"ORG_C\":\"NEWORG\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.FAIL_ON_CONFLICT);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        assertTrue(resultXml.contains("<ORG_C>NEWORG</ORG_C>"));
        // Unrelated non-empty field is preserved.
        assertTrue(resultXml.contains("<SSN>123-45-6789</SSN>"));
    }

    @Test
    void fillXfaFormPatchFailOnConflictSucceedsWhenValueMatchesExisting() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"123-45-6789\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.FAIL_ON_CONFLICT);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        assertTrue(resultXml.contains("<SSN>123-45-6789</SSN>"));
    }

    @Test
    void fillXfaFormPutReplacesEntireFormClearingUnprovidedFields() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PUT, PatchMode.OVERWRITE);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        // Provided field is written.
        assertTrue(resultXml.contains("<SSN>999-99-9999</SSN>"));
        // Fields the caller did not mention are gone (their previous values are cleared).
        assertFalse(resultXml.contains("9988"));
        assertFalse(resultXml.contains("6543"));
        // Including fields on an entirely-omitted branch (Page2).
        assertFalse(resultXml.contains("222222222"));
    }

    @Test
    void fillXfaFormPatchPreservesUnprovidedFields() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.OVERWRITE);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        assertTrue(resultXml.contains("<SSN>999-99-9999</SSN>"));
        // Unmentioned fields keep their existing values.
        assertTrue(resultXml.contains("9988"));
        assertTrue(resultXml.contains("6543"));
        assertTrue(resultXml.contains("222222222"));
    }

    @Test
    void fillXfaFormPutIgnoresPatchMode() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        // SSN's existing value is non-empty; under PATCH+IF_EMPTY it would be kept, but PUT always replaces.
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PUT, PatchMode.IF_EMPTY);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        // PUT overwrote the provided field regardless of patchMode...
        assertTrue(resultXml.contains("<SSN>999-99-9999</SSN>"));
        assertFalse(resultXml.contains("123-45-6789"));
        // ...and cleared unprovided fields.
        assertFalse(resultXml.contains("9988"));
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
