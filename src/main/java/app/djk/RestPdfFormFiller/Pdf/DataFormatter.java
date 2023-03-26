package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormDataException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

public class DataFormatter {
    public static String convertXmlToJsonString(String xml) throws JsonProcessingException {
        return convertXmlToJsonNode(xml).toPrettyString();

    }

    private static JsonNode convertXmlToJsonNode(String xml) throws JsonProcessingException {
        final var xmlMapper = new XmlMapper();
        return xmlMapper.readTree(xml);
    }

    /**
     * Converts a JSON representation of XFA form data to a String XML representation.
     * This method wraps the {@link #convertJsonToXml(String)} method.
     * @param json The JSON data object.
     * @return The XML Document representation of the data in <code>json</code>.
     * @throws ParserConfigurationException If there's a problem with creating the XML document.
     * @throws TransformerException If there's a problem with transforming the XML document into a string.
     * @throws InvalidXfaFormDataException If a JSON object is not in the correct format for an XFA form.
     */
    public static String convertJsonToXmlString(String json) throws ParserConfigurationException, TransformerException {
        final var xmlDocument = convertJsonToXml(json);

        final var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        final var strWriter = new StringWriter();
        transformer.transform(new DOMSource(xmlDocument), new StreamResult(strWriter));

        return strWriter.toString();
    }

    /**
     * Converts a JSON representation of XFA form data to its XML form.
     * The root XML and data elements are hard-coded because they are constant.
     * This method recurses through the remaining JSON elements and creates the XML elements.
     * @param json The JSON data object.
     * @return The XML Document representation of the data in <code>json</code>.
     * @throws ParserConfigurationException If there's a problem with creating the XML document.
     * @throws InvalidXfaFormDataException If a JSON object is not in the correct format for an XFA form.
     */
    public static Document convertJsonToXml(String json) throws ParserConfigurationException {
        final var xmlDocument = (DocumentBuilderFactory.newInstance()).newDocumentBuilder().newDocument();

        try {
            final var rootJsonNode = (new ObjectMapper()).readTree(json);


            // Creating the root element that an XFA form expects.
            // This element name is not expected to be in the JSON data object.
            final var rootElement = xmlDocument.createElement("xfa:datasets");
            rootElement.setAttribute("xmlns:xfa", "http://www.xfa.org/schema/xfa-data/1.0/");
            xmlDocument.appendChild(rootElement);

            // The JSON data object should only have one key, "data".
            if (rootJsonNode.getNodeType() != JsonNodeType.OBJECT || rootJsonNode.size() != 1) {
                throw new InvalidXfaFormDataException();
            }

            // Hard-coding the data element because the element name that I give the JSON
            // object is just "data". The XFA form expects the "xfa:data" element name.
            final var dataElement = xmlDocument.createElement("xfa:data");
            rootElement.appendChild(dataElement);

            // The "data" key should always have a JSON object as its value.
            final var dataNode = rootJsonNode.get("data");
            if (dataNode.getNodeType() != JsonNodeType.OBJECT) {
                throw new InvalidXfaFormDataException();
            }

            // Recurses through the data node and creates the XML elements.
            // This call modifies dataElement, and by extension, xmlDocument.
            convertJsonNodeToXmlElement(xmlDocument, dataElement, dataNode);

            return xmlDocument;
        } catch(JsonProcessingException ex) {
            throw new InvalidXfaFormException();
        }
    }

    /**
     * Recursively converts a <code>JsonNode</code> to XML elements.
     * This method modifies the <code>element</code> parameter by adding child elements to it.
     * @param xmlDocument The XML document to which the elements will be added.
     * @param element The XML element to which the child elements will be added.
     * @param jsonNode The JSON node to be converted. This should be the corresponding JSON value content
     *                 for the <code>element</code> parameter.
     */
    private static void convertJsonNodeToXmlElement(
            final @NotNull Document xmlDocument,
            @NotNull org.w3c.dom.Element element,
            @NotNull JsonNode jsonNode) {

        switch (jsonNode.getNodeType()) {
            case OBJECT -> jsonNode.fields().forEachRemaining((entry) -> {
                final var childElement = xmlDocument.createElement(entry.getKey());
                element.appendChild(childElement);
                convertJsonNodeToXmlElement(xmlDocument, childElement, entry.getValue());
            });
            case STRING -> {
                final var textNode = xmlDocument.createTextNode(jsonNode.asText());
                element.appendChild(textNode);
            }
        }
    }

    /**
     * This method converts the XML to JSON and calls the private <code>generateJsonSchema</code> method.
     * Here is the description from that method:
     * <br />
     * Recursive method that generates a simple JSON schema for the given node. In this simplified schema,
     * everything is either an object or a string. This only generates the type and properties keys; it does
     * not handle the rest of the schema specification. This method is strictly intended to be compatible with
     * the Power Automate custom connector dynamic schema parameter.
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
     * not handle the rest of the schema specification. This method is strictly intended to be compatible with
     * the Power Automate custom connector dynamic schema parameter.
     *
     * @param sourceNode The JSON node upon which to base the schema.
     * @return A JSON node representing the argument's schema.
     */
    private static @NotNull JsonNode generateJsonSchema(final @NotNull JsonNode sourceNode) {
        final var objectMapper = new ObjectMapper();
        final var schemaNode = objectMapper.createObjectNode();

        // TODO add array type
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


}
