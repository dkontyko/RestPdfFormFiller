package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.Pdf.DataFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataFormatterTest {

    @Test
    void testConvertXmlToJsonString() {
        String xml = "<root><child>value</child></root>";
        String expectedJson = "{\n  \"root\" : {\n    \"child\" : \"value\"\n  }\n}";
        try {
            String actualJson = DataFormatter.convertXmlToJsonString(xml);
            assertEquals(expectedJson, actualJson);
        } catch (Exception e) {
            fail("Exception should not be thrown");
        }
    }

    @Test
    void testConvertJsonToXmlString() {
        String json = "{ \"root\": { \"child\": \"value\" } }";
        String expectedXml = "<root><child>value</child></root>";
        try {
            String actualXml = DataFormatter.convertJsonToXmlString(json);
            assertEquals(expectedXml, actualXml);
        } catch (Exception e) {
            fail("Exception should not be thrown");
        }
    }

    // Add more tests for other methods in the DataFormatter class
}