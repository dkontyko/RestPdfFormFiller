package app.djk.RestPdfFormFiller.Pdf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.lowagie.text.pdf.PdfReader;
import org.jetbrains.annotations.NotNull;

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
     *
     * @param is An <code>InputStream</code> representing the DA 4187.
     * @return A pretty-printed XML String of the XFA form data (everything withing and including the datasets node).
     * @throws IOException          If there's a problem with creating the <code>PDFReader</code>.
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
     *
     * @param pdfBytes A <code>byte[]</code> representing the DA 4187.
     * @return Same as the overloaded method.
     * @throws IOException          Same as the overloaded method.
     * @throws TransformerException Same as the overloaded method.
     */
    public static String get4187DatasetNodeAsString(byte[] pdfBytes) throws IOException, TransformerException {
        return get4187DatasetNodeAsString(new ByteArrayInputStream(pdfBytes));
    }

    public static String convertXmlToJsonString(String xml) throws JsonProcessingException {
        return convertXmlToJsonNode(xml).toPrettyString();

    }

    private static JsonNode convertXmlToJsonNode(String xml) throws JsonProcessingException {
        var xmlMapper = new XmlMapper();
        return xmlMapper.readTree(xml);
    }

    /**
     * This method converts the XML to JSON and calls the private <code>generateJsonSchema</code> method.
     * Here is the description from that method:
     *
     * Recursive method that generates a simple JSON schema for the given node. In this simplified schema,
     * everything is either an object or a string. This only generates the type and properties keys; it does
     * not handle the rest of the schema specification. This method is strictly intended to be compatiable with
     * the Power Automate custom commector dynamic schema parameter.
     *
     * @param xml The XML data from the form.
     * @return A pretty-printed String of a JSON schema object representing the JSON schema version of the form schema.
     */
    public static String generateJsonSchema(final String xml) throws JsonProcessingException {
        final var topNode = convertXmlToJsonNode(xml);
        return generateJsonSchema(topNode).toPrettyString();
    }

    /**
     * Recursive method that generates a simple JSON schema for the given node. In this simplified schema,
     * everything is either an object or a string. This only generates the type and properties keys; it does
     * not handle the rest of the schema specification. This method is strictly intended to be compatiable with
     * the Power Automate custom commector dynamic schema parameter.
     *
     * @param sourceNode The JSON node upon which to base the schema.
     * @return A JSON node representing the argument's schema.
     */
    private static @NotNull JsonNode generateJsonSchema(final @NotNull JsonNode sourceNode) {
        final var objectMapper = new ObjectMapper();
        final var schemaNode = objectMapper.createObjectNode();

        // if object, then "type": "object" and "properties": { <child schemas>}
        // else string, "<nodeName>": { "type": "string" }
        if (sourceNode.getNodeType() == JsonNodeType.OBJECT) {
            schemaNode.put("type", "object");
            final var schemaObjectProperties = objectMapper.createObjectNode();
            schemaNode.set("properties", schemaObjectProperties);

            // recursing on the child nodes and feeding their schemas into the properties schema node
            var sourceChildren = sourceNode.fields();
            sourceChildren.forEachRemaining((entry) -> {
                var childSchema = generateJsonSchema(entry.getValue());
                schemaObjectProperties.set(entry.getKey(), childSchema);
            });
        } else {
            schemaNode.put("type", "string");
        }

        return schemaNode;
    }

    public static boolean isXfaForm(final InputStream pdfStream) throws IOException {
        try(var pdfReader = new PdfReader(pdfStream)) {
            return pdfReader.getAcroFields().getXfa().isXfaPresent();
        }
    }

    public static boolean isXfaForm(final byte[] pdfBytes) throws IOException {
        return isXfaForm(new ByteArrayInputStream(pdfBytes));
    }

    //TODO Given JSON content for specified form, get PDF with form fields filled with content
}
