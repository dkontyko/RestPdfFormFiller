package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormDataException;
import app.djk.RestPdfFormFiller.projectExceptions.WriteConflictException;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.PRStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void fillXfaFormPatchSynchronizesAcroFormValueAndAppearance() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var expectedRemarks = "AcroForm appearance validation";
        final var formData = "{\"data\":{\"form1\":{\"Page1\":{\"REMARKS\":\""
                + expectedRemarks + "\"}}}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PATCH, PatchMode.OVERWRITE);

        assertAcroFormValueAndAppearance(
                filledBytes, "form1[0].Page1[0].REMARKS[0]", expectedRemarks);
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
        assertAcroFormValueAndAppearance(
                filledBytes, "form1[0].Page1[0].REMARKS[0]", "");
    }

    @Test
    void fillXfaFormPutClearsOmittedAcroFormAppearance() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        final var previousRemarks = "Remarks appearance that must be cleared";
        final var remarksPatch = "{\"data\":{\"form1\":{\"Page1\":{\"REMARKS\":\""
                + previousRemarks + "\"}}}}";
        final var putData = "{\"data\":{\"form1\":{\"Page1\":{\"SSN\":\"999-99-9999\"}}}}";

        // Write a value unique to this test so a stale widget appearance is distinguishable from the source form.
        final var patchedBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, remarksPatch, WriteMode.PATCH, PatchMode.OVERWRITE);
        assertAcroFormValueAndAppearance(
                patchedBytes, "form1[0].Page1[0].REMARKS[0]", previousRemarks);

        final var filledBytes = RestPdfApi.fillXfaForm(
                patchedBytes, putData, WriteMode.PUT, PatchMode.OVERWRITE);

        assertAcroFormValueAndAppearance(
                filledBytes, "form1[0].Page1[0].REMARKS[0]", "", previousRemarks);
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

    @Test
    void fillXfaFormPutWithEmptyDataClearsAllFields() throws Exception {
        final var samplePdfBytes = readSampleDa4187Pdf();
        // Empty data object: no form-root provided, so PUT should clear every field.
        final var formData = "{\"data\":{}}";

        final var filledBytes = RestPdfApi.fillXfaForm(
                samplePdfBytes, formData, WriteMode.PUT, PatchMode.OVERWRITE);
        final var resultXml = RestPdfApi.getXfaDatasetNodeAsString(filledBytes);

        // All previously-populated field values are gone.
        assertFalse(resultXml.contains("123-45-6789"));
        assertFalse(resultXml.contains("9988"));
        assertFalse(resultXml.contains("6543"));
        assertFalse(resultXml.contains("222222222"));
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

    private static void assertAcroFormValueAndAppearance(final byte[] pdfBytes, final String fieldName,
                                                          final String expectedValue) throws Exception {
        assertAcroFormValueAndAppearance(pdfBytes, fieldName, expectedValue, null);
    }

    private static void assertAcroFormValueAndAppearance(final byte[] pdfBytes, final String fieldName,
                                                          final String expectedValue,
                                                          final String textThatMustNotAppear) throws Exception {
        try (final var reader = new PdfReader(pdfBytes)) {
            final var item = reader.getAcroFields().getFieldItem(fieldName);
            assertTrue(item != null, () -> "Missing AcroForm field " + fieldName);

            final PdfDictionary value = item.getValue(0);
            final PdfDictionary widget = item.getWidget(0);
            assertAcroFormValue(fieldName, value, expectedValue);
            assertNormalAppearance(fieldName, widget, expectedValue, textThatMustNotAppear);
        }
    }

    private static void assertAcroFormValue(final String fieldName, final PdfDictionary value,
                                            final String expectedValue) {
        assertTrue(value != null, () -> "Missing value dictionary for " + fieldName);
        final var fieldValue = value.getAsString(PdfName.V);
        assertTrue(fieldValue != null, () -> "Missing value for " + fieldName);
        assertEquals(expectedValue, fieldValue.toUnicodeString());
    }

    private static void assertNormalAppearance(final String fieldName, final PdfDictionary widget,
                                               final String expectedValue, final String textThatMustNotAppear)
            throws IOException {
        assertTrue(widget != null, () -> "Missing widget for " + fieldName);

        final var appearanceDictionary = widget.getAsDict(PdfName.AP);
        assertTrue(appearanceDictionary != null, () -> "Missing appearance dictionary for " + fieldName);

        final var normalAppearance = appearanceDictionary.getAsStream(PdfName.N);
        final var appearance = assertInstanceOf(PRStream.class, normalAppearance,
                () -> "Normal appearance is not a stream for " + fieldName);

        if (expectedValue.isEmpty() && textThatMustNotAppear == null) {
            return;
        }

        final var appearanceBytes = PdfReader.getStreamBytes(appearance);
        final var appearanceText = new String(appearanceBytes, StandardCharsets.ISO_8859_1);
        if (!expectedValue.isEmpty()) {
            assertTrue(appearanceText.contains(expectedValue),
                    () -> "Appearance did not contain the expected value for " + fieldName);
        }
        if (textThatMustNotAppear != null) {
            assertFalse(appearanceText.contains(textThatMustNotAppear),
                    () -> "Appearance still contained stale text for " + fieldName);
        }
    }
}
