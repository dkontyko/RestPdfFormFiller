package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.DataFormatter;
import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidSessionIdException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.graph.core.ClientException;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Azure Functions with HTTP Trigger.
 */
public class HttpTriggerFunctions {
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

            if (!RestPdfApi.FORM_DATA_FORMATS.contains(returnDataFormat)) {
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
            if (request.getBody().isEmpty()) {
                // If no file sent in body, then retrieve file from SPO.
                throw new EmptyRequestBodyException();
            } else {
                final var requestBody = request.getBody().get();
                // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
                var requestBytes = Base64.getDecoder().decode(requestBody);
                context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

                var dataSchema = DataFormatter.generateJsonSchema(RestPdfApi.getXfaDatasetNodeAsString(requestBytes));
                return request.createResponseBuilder(HttpStatus.OK).body(dataSchema).build();
            }
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

        /*
            New plan:
            Send giant JSON object in body with the form data and the PDF file.
            The JSON object will have the following structure:
            {
                "data": {
                    "field1": "value1",
                    "field2": "value2",
                    ...
                }
                "file": "base64 encoded PDF file",
            }
         */
        return errorHandler(request, context, () -> {

            //final var fileStream = getFileInputStreamFromSpo(request);
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);

            final var requestJson = (new ObjectMapper()).readTree(requestBody);

            if (!requestJson.has("data") || !requestJson.has("file")) {
                throw new IllegalArgumentException();
            }

            // Get schemas from data and file.
            // Check if data schema is contained in file schema.

            final var submittedData = requestJson.get("data");

            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            var fileBytes = Base64.getDecoder().decode(requestJson.get("file").textValue());
            context.getLogger().info("Form length (number of bytes): " + fileBytes.length);

            final var formXfaData = RestPdfApi.getXfaDatasetNodeAsString(fileBytes);
            final var isDataSubsetOfForm = DataFormatter.validateFormDataSchema(submittedData, formXfaData);

            if (!isDataSubsetOfForm) {
                throw new IllegalArgumentException();
            }

            // need to create a new input stream from the fileBytes
            final var fileStream = new java.io.ByteArrayInputStream(fileBytes);
            final var filledFormStream = RestPdfApi.fillXfaForm(fileStream, requestBody);
            final var base64EncodedForm = Base64.getEncoder().encode(filledFormStream.toString().getBytes());

            return request.createResponseBuilder(HttpStatus.OK).body(base64EncodedForm).build();
            //return request.createResponseBuilder(HttpStatus.NOT_IMPLEMENTED).build();
        });
    }

    /**
     * This abstracts all the error handling to a single method, to avoid duplication of the catch blocks.
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
            /*
             EDIT: This was a bad idea. (Thank you, GitHub Copilot, for autocompleting that sentence.)
             I am devolving these exceptions to their respective methods as soon as I have the code working.
             ------------------
             ORIGINAL:
             AFAIK, lambdas cannot inherently throw checked exceptions.
             So I wrote a custom functional interface that can throw an exception. But
             in order to do so, you have to declare every possible exception that may be
             thrown in the method signature. So instead, I'm just declaring the base Exception
             class. But then I can't handle specific exceptions without downcasting the exception
             to its original type. So that's what the inner try-catch block does, until I find a
             better way.
            */
            try {
                return function.get();
            } catch (Exception e) {
                context.getLogger().warning("Caught exception: " + e);
                context.getLogger().warning(Arrays.toString(e.getStackTrace()));
                var eClass = e.getClass();
                throw eClass.cast(e);
            }
        } // Local project exceptions
        catch (EmptyRequestBodyException e) {
            context.getLogger().warning("Empty request body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        } catch (InvalidXfaFormException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid XFA form.").build();
        } catch (InvalidReturnDataFormatException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid format parameter: Must be 'json' or 'xml'.").build();
        } catch (InvalidSessionIdException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid session ID.").build();
        }
        // Dependency and built-in exceptions
        catch (ClientException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Could not retrieve file.").build();
        } catch (NumberFormatException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid integer argument in request.").build();
        } catch (IllegalArgumentException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid argument in request.").build();
        } catch (Exception e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        }
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
}