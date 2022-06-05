package app.djk.RestPdfFormFiller.Pdf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.lowagie.text.pdf.PdfReader;
import jdk.jshell.spi.ExecutionControl;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

public class RestPdfApi {

    /**
     * Gets the XML form field data from the given DA 4187. (This may work with other XFA forms, but
     * it's specifically designed to work with the 4187 for now.
     * @param is An <code>InputStream</code> representing the DA 4187.
     * @return A pretty-printed XML String of the XFA form data (everything withing and including the datasets node).
     * @throws IOException If there's a problem with creating the <code>PDFReader</code>.
     * @throws TransformerException If there's a problem with transforming the extracted datasets node into a string.
     */
    public static String get4187DatasetNodeAsString(InputStream is) throws IOException, TransformerException {
        try (var newReader = new PdfReader(is)) {
            //This is the node that contains the XFA form data.
            final var datasetsNode = newReader.getAcroFields().getXfa().getDatasetsNode();

            final var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            var strWriter = new StringWriter();

            // Copying the datasets node into a pretty-printed StringWriter object.
            transformer.transform(new DOMSource(datasetsNode), new StreamResult(strWriter));

            return strWriter.toString();
        }
    }

    /**
     * Wraps the <code>byte[]</code> in an <code>InputStream</code> and calls
     * <code>get4187DatasetNodeAsString(InputStream is)</code>.
     * @param pdfBytes A <code>byte[]</code> representing the DA 4187.
     * @return Same as the overloaded method.
     * @throws IOException Same as the overloaded method.
     * @throws TransformerException Same as the overloaded method.
     */
    public static String get4187DatasetNodeAsString(byte[] pdfBytes) throws IOException, TransformerException {
        return get4187DatasetNodeAsString(new ByteArrayInputStream(pdfBytes));
    }

    public static String convertXmlToJsonString(String xml) throws JsonProcessingException {
        return convertXmlToJsonNode(xml).toPrettyString();

    }

    public static JsonNode convertXmlToJsonNode(String xml) throws JsonProcessingException {
        var xmlMapper = new XmlMapper();
        return xmlMapper.readTree(xml);
    }

    /**
     *
     * @param xml The XML data from the form.
     * @return A pretty-printed String of a JSON schema object representing the JSON schema version of the form schema.
     */
    public static String generateJsonSchema(String xml) throws JsonProcessingException, ExecutionControl.NotImplementedException {
        var topNode = convertXmlToJsonNode(xml);
        return generateJsonSchema(topNode, null).toPrettyString();
    }

    // if object, then "type": "object" and "properties": { <child nodes>}
    // else string, "<nodeName>": { "type": "string" }
    private static JsonNode generateJsonSchema(JsonNode sourceNode, ObjectNode parentSchema) throws ExecutionControl.NotImplementedException {
        if(parentSchema == null) {
            parentSchema = (new ObjectMapper().createObjectNode());
        }

        throw new ExecutionControl.NotImplementedException("Method not implemented");
    }

    //TODO Given JSON content for specified form, get PDF with form fields filled with content
}
