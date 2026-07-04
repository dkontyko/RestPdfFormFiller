package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.DataFormatter;
import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidSessionIdException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.SafeToReturnIllegalArgumentException;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeType;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.logging.Level;

/**
 * Azure Functions with HTTP Trigger.
 */
public class HttpTriggerFunctions {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Azure Function that receives a Base64-encoded PDF file and returns the XFA form field data.
     * This function takes an HTTP POST request. It requires a query parameter of <code>format</code>
     * set to either <code>json</code> or <code>xml</code> for the return format of the form data.
     * It also requires the request body to have the binary PDF file encoded in base64.
     *
     * @param request Azure Function parameter representing the HTTP request.
     * @param context Azure Function parameter representing the execution context.
     * @return An HTTP Response indicating the result of the request. If successful, the body will contain the
     * XFA form field data.
     */
    @FunctionName("GetXfaData")
    public HttpResponseMessage getXfaData(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
            // Checking if a body was received in the HTTP request.
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);

            // Return format supplied as URL query parameter. See RestPdfApi for valid formats.
            final var returnDataFormat = request.getQueryParameters().get("format");

            if (returnDataFormat == null || !RestPdfApi.FORM_DATA_FORMATS.contains(returnDataFormat)) {
                throw new InvalidReturnDataFormatException();
            }

            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            final var requestBytes = Base64.getDecoder().decode(requestBody);
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            var datasetsString = RestPdfApi.getXfaDatasetNodeAsString(requestBytes);
            if (returnDataFormat.equals("json")) {
                datasetsString = DataFormatter.convertXmlToJsonString(datasetsString);
            }
            return request.createResponseBuilder(HttpStatus.OK).body(datasetsString).build();
        });
    }

    @FunctionName("GetXfaSchema")
    public HttpResponseMessage getXfaSchema(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);
            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            final var requestBytes = Base64.getDecoder().decode(requestBody);
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            final var dataSchema = DataFormatter.generateJsonSchema(RestPdfApi.getXfaDatasetNodeAsString(requestBytes));
            return request.createResponseBuilder(HttpStatus.OK).body(dataSchema).build();
        });
    }

    @FunctionName("FillXfaData")
    public HttpResponseMessage fillXfaData(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {


        return errorHandler(request, context, () -> {
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);
            final var fillRequest = parseFillRequest(requestBody);
            final var templateBytes = Base64.getDecoder().decode(fillRequest.templateBase64());

            try {
                if (!RestPdfApi.isXfaForm(templateBytes)) {
                    throw new InvalidXfaFormException();
                }
            } catch (java.io.IOException e) {
                throw new InvalidXfaFormException();
            }

            if (fillRequest.validateOnly()) {
                return request.createResponseBuilder(HttpStatus.OK).body("Validation succeeded.").build();
            }

            final var templateStream = new ByteArrayInputStream(templateBytes);

            final var filledPdfBytes = RestPdfApi.fillXfaForm(templateStream, fillRequest.formDataJson());
            final var base64EncodedForm = Base64.getEncoder().encodeToString(filledPdfBytes);

            return request.createResponseBuilder(HttpStatus.OK).body(base64EncodedForm).build();
        });
    }


    /**
     * This abstracts all the error handling to a single method, to avoid duplication of the catch blocks.
     * <p>
     * Error messages from {@link SafeToReturnIllegalArgumentException} are returned to callers as-is.
     * Other {@link IllegalArgumentException} messages are replaced with a generic response.
     *
     * @param request  HTTP request from the caller.
     * @param context  ExecutionContext from the caller.
     * @param function Caller-specific function code to be executed.
     * @return An HTTP response with the result of the function operation.
     */
    private HttpResponseMessage errorHandler(final HttpRequestMessage<?> request,
                                             final ExecutionContext context,
                                             final ThrowingSupplier<HttpResponseMessage> function) {
        try {
            return function.get();
        } // Local project exceptions
        catch (EmptyRequestBodyException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "No content supplied in body.", e);
        } catch (InvalidXfaFormException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid XFA form.", e);
        } catch (InvalidReturnDataFormatException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid format parameter: Must be 'json' or 'xml'.", e);
        } catch (InvalidSessionIdException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid session ID.", e);
        } catch (SafeToReturnIllegalArgumentException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    e.getMessage(), e);
        }
        // Dependency and built-in exceptions
        catch (NumberFormatException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid integer argument in request.", e);
        } catch (IllegalArgumentException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid argument in request.", e);
        } catch (Exception e) {
            return logAndRespond(request, context, Level.SEVERE, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Request failed.", e);
        }
    }

    private static HttpResponseMessage logAndRespond(final HttpRequestMessage<?> request,
                                                     final ExecutionContext context,
                                                     final Level level,
                                                     final HttpStatus status,
                                                     final String responseBody,
                                                     final Throwable throwable) {
        context.getLogger().log(level, responseBody, throwable);
        return request.createResponseBuilder(status).body(responseBody).build();
    }

    /**
     * The built-in <code>Supplier</code> does not throw checked exceptions, so this provides that capability
     * for use in lambda expressions.
     *
     * @param <T> The supplier return type.
     */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Parses and validates request payload for <code>FillXfaData</code>.
     *
     * @param requestBody Raw HTTP request body content.
     * @return Parsed fill request payload.
     * @throws SafeToReturnIllegalArgumentException If the payload is not valid for this endpoint's contract.
     */
    private static FillRequest parseFillRequest(final String requestBody) {
        final var rootNode = parseRequestBodyAsJson(requestBody);

        final var templateNode = rootNode.path("templateBase64");
        if (templateNode.getNodeType() != JsonNodeType.STRING) {
            throw new SafeToReturnIllegalArgumentException("Request field 'templateBase64' must be a non-empty string.");
        }
        final var templateBase64 = templateNode.stringValue();
        if (templateBase64 == null || templateBase64.isBlank()) {
            throw new SafeToReturnIllegalArgumentException("Request field 'templateBase64' must be a non-empty string.");
        }

        final var formDataNode = rootNode.path("formData");
        if (!formDataNode.isObject()) {
            throw new SafeToReturnIllegalArgumentException("Request field 'formData' must be a JSON object.");
        }

        if (!formDataNode.has("data") || formDataNode.size() != 1 || !formDataNode.path("data").isObject()) {
            throw new SafeToReturnIllegalArgumentException("Request field 'formData' must contain only a 'data' object.");
        }

        final var payloadMode = parsePayloadMode(rootNode.path("payloadMode"));
        final var writeMode = parseWriteMode(rootNode.path("writeMode"));
        final var validateOnly = parseValidateOnly(rootNode.path("validateOnly"));

        return new FillRequest(templateBase64, formDataNode.toString(), payloadMode, writeMode, validateOnly);
    }

    private static PayloadMode parsePayloadMode(final JsonNode payloadModeNode) {
        if (payloadModeNode.isMissingNode() || payloadModeNode.isNull()) {
            return PayloadMode.PARTIAL;
        }
        final var mode = payloadModeNode.getNodeType() == JsonNodeType.STRING
                ? PayloadMode.fromValue(payloadModeNode.stringValue()) : null;
        if (mode == null) {
            throw new SafeToReturnIllegalArgumentException(
                    "Request field 'payloadMode' must be one of the following strings: partial, complete.");
        }
        return mode;
    }

    private static WriteMode parseWriteMode(final JsonNode writeModeNode) {
        if (writeModeNode.isMissingNode() || writeModeNode.isNull()) {
            return WriteMode.OVERWRITE;
        }
        final var mode = writeModeNode.getNodeType() == JsonNodeType.STRING
                ? WriteMode.fromValue(writeModeNode.stringValue()) : null;
        if (mode == null) {
            throw new SafeToReturnIllegalArgumentException(
                    "Request field 'writeMode' must be one of the following strings: overwrite, ifEmpty, failOnConflict.");
        }
        return mode;
    }

    private static boolean parseValidateOnly(final JsonNode validateOnlyNode) {
        if (validateOnlyNode.isMissingNode() || validateOnlyNode.isNull()) {
            return false;
        }
        if (!validateOnlyNode.isBoolean()) {
            throw new SafeToReturnIllegalArgumentException("Request field 'validateOnly' must be a boolean.");
        }
        return validateOnlyNode.booleanValue();
    }

    /**
     * Parses the request body into a JSON node and translates parsing failures into a caller-facing
     * <code>SafeToReturnIllegalArgumentException</code>.
     *
     * @param requestBody Raw HTTP request body content.
     * @return Parsed JSON root node.
     * @throws SafeToReturnIllegalArgumentException If parsing fails.
     */
    private static JsonNode parseRequestBodyAsJson(final String requestBody) {
        try {
            return OBJECT_MAPPER.readTree(requestBody);
        } catch (RuntimeException e) {
            throw new SafeToReturnIllegalArgumentException("Request body must be valid JSON.", e);
        }
    }

    /**
     * Request payload contract for <code>FillXfaData</code>.
     *
     * @param templateBase64 Base64-encoded source PDF content.
     * @param formDataJson   JSON object string containing a single <code>data</code> object.
     * @param payloadMode    Describes payload completeness expectations.
     *                       <code>PARTIAL</code> means omitted fields are treated as "no change".
     *                       <code>COMPLETE</code> means the client intends to provide a complete
     *                       object for the target schema.
     * @param writeMode      Describes how provided values should interact with existing form values.
     *                       <code>OVERWRITE</code> means provided values replace existing values,
     *                       <code>IF_EMPTY</code> means provided values only write into empty targets,
     *                       and <code>FAIL_ON_CONFLICT</code> means conflicting non-empty targets
     *                       should be rejected.
     * @param validateOnly   If true, run request validation and return success/failure without returning
     *                       a filled document body. This mode is for validation workflows where callers
     *                       want contract checks without emitting filled output.
     */
    private record FillRequest(
            String templateBase64,
            String formDataJson,
            PayloadMode payloadMode,
            WriteMode writeMode,
            boolean validateOnly) {
    }

    /**
     * Payload completeness mode for <code>FillXfaData</code>.
     */
    private enum PayloadMode {
        /**
         * Client is sending a subset of fields to be considered as a patch.
         */
        PARTIAL("partial"),
        /**
         * Client is sending a full object intended to represent the complete data shape.
         */
        COMPLETE("complete");

        private final String value;

        PayloadMode(final String value) {
            this.value = value;
        }

        static PayloadMode fromValue(final String value) {
            for (final var mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * Write conflict mode for <code>FillXfaData</code>.
     */
    private enum WriteMode {
        /**
         * Provided values replace existing target values.
         */
        OVERWRITE("overwrite"),
        /**
         * Provided values are applied only when the existing target value is empty.
         */
        IF_EMPTY("ifEmpty"),
        /**
         * Provided values that conflict with non-empty existing target values should be rejected.
         */
        FAIL_ON_CONFLICT("failOnConflict");

        private final String value;

        WriteMode(final String value) {
            this.value = value;
        }

        static WriteMode fromValue(final String value) {
            for (final var mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }
}