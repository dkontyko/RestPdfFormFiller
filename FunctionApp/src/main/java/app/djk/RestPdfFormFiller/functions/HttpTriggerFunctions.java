package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.DataFormatter;
import app.djk.RestPdfFormFiller.Pdf.PatchMode;
import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.Pdf.WriteMode;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidSessionIdException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.SafeToReturnIllegalArgumentException;
import app.djk.RestPdfFormFiller.projectExceptions.WriteConflictException;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeType;

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
            HttpRequestMessage<Optional<byte[]>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
            // The PDF arrives as the raw binary request body (no Base64), so a Power Automate flow can pass a
            // SharePoint/OneDrive "Get file content" result straight through without any conversion.
            final var requestBytes = request.getBody().orElseThrow(EmptyRequestBodyException::new);
            if (requestBytes.length == 0) {
                throw new EmptyRequestBodyException();
            }

            // Return format supplied as URL query parameter. See RestPdfApi for valid formats.
            final var returnDataFormat = request.getQueryParameters().get("format");

            if (returnDataFormat == null || !RestPdfApi.FORM_DATA_FORMATS.contains(returnDataFormat)) {
                throw new InvalidReturnDataFormatException();
            }

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
            HttpRequestMessage<Optional<byte[]>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
            // The PDF arrives as the raw binary request body (no Base64), matching the other read endpoint.
            final var requestBytes = request.getBody().orElseThrow(EmptyRequestBodyException::new);
            if (requestBytes.length == 0) {
                throw new EmptyRequestBodyException();
            }
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

            final var filledPdfBytes = RestPdfApi.fillXfaForm(
                    templateBytes, fillRequest.formDataJson(), fillRequest.writeMode(), fillRequest.patchMode());

            // Return the filled PDF as raw binary (application/pdf) so Power Automate treats the response as a file
            // that drops straight into a "Create file" action -- no Base64-to-binary conversion, and no risk of the
            // document being corrupted by passing it through a JSON string layer.
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/pdf")
                    .body(filledPdfBytes)
                    .build();
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
        } catch (WriteConflictException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.CONFLICT,
                    e.getMessage(), e);
        }
        // Dependency and built-in exceptions
        catch (NumberFormatException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid integer argument in request.", e);
        } catch (IllegalArgumentException e) {
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid argument in request.", e);
        } catch (java.io.IOException e) {
            // Every request in this app is processed against in-memory PDF bytes, so an IOException here means the
            // caller's PDF could not be parsed rather than a genuine infrastructure failure. Report it as a 400.
            return logAndRespond(request, context, Level.WARNING, HttpStatus.BAD_REQUEST,
                    "Invalid or corrupted PDF file.", e);
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

        final var writeMode = parseWriteMode(rootNode.path("writeMode"));

        final var patchModeNode = rootNode.path("patchMode");
        final var patchModeProvided = !(patchModeNode.isMissingNode() || patchModeNode.isNull());
        final var patchMode = parsePatchMode(patchModeNode);
        // patchMode is the collision policy for a merge, so it is meaningless for a full replacement. Reject the
        // contradictory combination explicitly rather than silently ignoring it, so a caller who expected their
        // provided values to be protected is not surprised by a full overwrite.
        if (writeMode == WriteMode.PUT && patchModeProvided) {
            throw new SafeToReturnIllegalArgumentException(
                    "Request field 'patchMode' is only valid when 'writeMode' is 'patch'.");
        }

        final var validateOnly = parseValidateOnly(rootNode.path("validateOnly"));

        return new FillRequest(templateBase64, formDataNode.toString(), writeMode, patchMode, validateOnly);
    }

    private static WriteMode parseWriteMode(final JsonNode writeModeNode) {
        if (writeModeNode.isMissingNode() || writeModeNode.isNull()) {
            return WriteMode.PATCH;
        }
        final var mode = writeModeNode.getNodeType() == JsonNodeType.STRING
                ? WriteMode.fromValue(writeModeNode.stringValue()) : null;
        if (mode == null) {
            throw new SafeToReturnIllegalArgumentException(
                    "Request field 'writeMode' must be one of the following strings: patch, put.");
        }
        return mode;
    }

    private static PatchMode parsePatchMode(final JsonNode patchModeNode) {
        if (patchModeNode.isMissingNode() || patchModeNode.isNull()) {
            return PatchMode.OVERWRITE;
        }
        final var mode = patchModeNode.getNodeType() == JsonNodeType.STRING
                ? PatchMode.fromValue(patchModeNode.stringValue()) : null;
        if (mode == null) {
            throw new SafeToReturnIllegalArgumentException(
                    "Request field 'patchMode' must be one of the following strings: overwrite, ifEmpty, failOnConflict.");
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
     * @param writeMode      Overall fill strategy. <code>PATCH</code> merges the request into the existing form
     *                       (omitted fields preserved); <code>PUT</code> replaces the whole form (omitted fields
     *                       cleared).
     * @param patchMode      Collision policy for provided fields under <code>PATCH</code>.
     *                       <code>OVERWRITE</code> replaces existing values, <code>IF_EMPTY</code> writes only into
     *                       empty targets, and <code>FAIL_ON_CONFLICT</code> rejects a different non-empty target.
     *                       Ignored under <code>PUT</code> (and rejected if supplied with it).
     * @param validateOnly   If true, run request validation and return success/failure without returning
     *                       a filled document body. This mode is for validation workflows where callers
     *                       want contract checks without emitting filled output.
     */
    private record FillRequest(
            String templateBase64,
            String formDataJson,
            WriteMode writeMode,
            PatchMode patchMode,
            boolean validateOnly) {
    }
}