package app.djk.RestPdfFormFiller.Pdf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.jetbrains.annotations.NotNull;

public class DataFormatter {
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
