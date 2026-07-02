package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormDataException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFormatterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void convertXmlToJsonStringProducesExpectedJsonStructure() {
        final var xml = "<root><child>value</child></root>";

        final var actualJson = DataFormatter.convertXmlToJsonString(xml);
        final var expectedJson = "{\"child\":\"value\"}";

        assertEquals(OBJECT_MAPPER.readTree(expectedJson), OBJECT_MAPPER.readTree(actualJson));
    }

    @Test
    void convertJsonToXmlStringProducesExpectedXfaElements() throws Exception {
        final var json = "{\"data\":{\"name\":\"Jane\",\"city\":\"Seattle\"}}";

        final var actualXml = DataFormatter.convertJsonToXmlString(json);

        assertTrue(actualXml.contains("<xfa:datasets"));
        assertTrue(actualXml.contains("<xfa:data>"));
        assertTrue(actualXml.contains("<name>Jane</name>"));
        assertTrue(actualXml.contains("<city>Seattle</city>"));
    }

    @Test
    void convertJsonToXmlStringRejectsInvalidTopLevelShape() {
        final var invalidJson = "{\"data\":{},\"extra\":{}}";

        assertThrows(InvalidXfaFormDataException.class, () -> DataFormatter.convertJsonToXmlString(invalidJson));
    }

    @Test
    void generateJsonSchemaBuildsObjectAndLeafStringTypes() {
        final var xml = "<root><customer><name>Jane</name></customer></root>";

        final var schemaJson = DataFormatter.generateJsonSchema(xml);
        final var schemaNode = OBJECT_MAPPER.readTree(schemaJson);

        assertEquals("object", schemaNode.path("type").stringValue());
        assertEquals("object", schemaNode.path("properties").path("customer").path("type").stringValue());
        assertEquals(
                "string",
                schemaNode.path("properties")
                        .path("customer")
                        .path("properties")
                        .path("name")
                        .path("type")
                        .stringValue());
    }
}
