package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

/**
 * Azure Functions with HTTP Trigger.
 */
public class HttpTriggerFunctions {

    /**
     * Azure Function that receives a Base64-encoded PDF file and returns the XFA form field data.
     *
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

            if(!RestPdfApi.FORM_DATA_FORMATS.contains(returnDataFormat)) {
                throw new InvalidReturnDataFormatException();
            }

            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            final var requestBytes = Base64.getDecoder().decode(requestBody);
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            var datasetsString = RestPdfApi.get4187DatasetNodeAsString(requestBytes);
            if(returnDataFormat.equals("json")) {
                datasetsString = RestPdfApi.convertXmlToJsonString(datasetsString);
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
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        // Checking if a body was received in the HTTP request.
        if(request.getBody().isEmpty()) {
            context.getLogger().warning("No content supplied in body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        }

        // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
        var requestBytes = Base64.getDecoder().decode(request.getBody());
        context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

        try {
            var dataSchema = RestPdfApi.generateJsonSchema(RestPdfApi.get4187DatasetNodeAsString(requestBytes));
            return request.createResponseBuilder(HttpStatus.OK).body(dataSchema).build();
        } catch (JsonProcessingException e) {
            // This is above IOException because JsonProcessingExceptions inherits from it.
            context.getLogger().warning(e.toString()); //TODO Move this to called method.
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Converting XML to JSON failed.").build();

        } catch (IOException e) {
            context.getLogger().warning("Unable to create InputStream."); //TODO Move this to called method.
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();

        } catch (TransformerException e) {
            context.getLogger().warning("DOM Transform failed."); //TODO Move this to called method.
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        }
    }

    @FunctionName("FillXfaData")
    public HttpResponseMessage fillXfaData(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        return request.createResponseBuilder(HttpStatus.NOT_IMPLEMENTED).build();

    }

    @FunctionName("FillXfaFormDataPart1")
    public HttpResponseMessage fillXfaFormDataPart1(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);
            final var requestBytes = Base64.getDecoder().decode(requestBody);
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            if(!RestPdfApi.isXfaForm(requestBytes)) {
                throw new InvalidXfaFormException();
            }

            final var sessionId = FileSessions.storeFile(requestBytes);
            context.getLogger().info("Stored file successfully.");
            return request.createResponseBuilder(HttpStatus.CREATED).header("sessionId", sessionId).build();
        });
    }

    /**
     * Takes a session ID in the header and a JSON string in the body of the form field data. Fills the
     * XFA PDF associated with the session with the submitted data. Returns the PDF in binary.
     * @param request Azure Function parameter representing the HTTP request.
     * @param context Azure Function parameter representing the execution context.
     * @return An HTTP Response indicating the result of the request. If successful, the body will contain
     * XFA form with the fields filled as specified in the request.
     */
    @FunctionName("FillXfaFormDataPart2")
    public HttpResponseMessage fillXfaFormDataPart2(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        // Checking if a body was received in the HTTP request.
        if(request.getBody().isEmpty()) {
            context.getLogger().warning("No content supplied in body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        }

        // getting session ID to retrieve file
        final var sessionId = request.getHeaders().get("sessionId");
        try {
            // Verifying that session ID is valid base64.
            Base64.getDecoder().decode(sessionId);
        } catch (IllegalArgumentException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid session ID.").build();
        }

        final var fileBytes = FileSessions.retrieveFile(sessionId);

        // Still need to write logic to fill XFA.

        return request.createResponseBuilder(HttpStatus.METHOD_NOT_ALLOWED).body("Not implemented.").build();

    }

    /**
     * This abstracts all the error handling to a single method, to avoid duplication of the catch blocks.
     * @param request HTTP request from the caller.
     * @param context ExecutionContext from the caller.
     * @param function Caller-specific function code to be executed.
     * @return An HTTP response with the result of the function operation.
     */
    private HttpResponseMessage errorHandler(final HttpRequestMessage<?> request,
                                             final ExecutionContext context,
                                             final ThrowingSupplier<HttpResponseMessage> function) {
        try {
            /*
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
                context.getLogger().warning(e.toString());
                var eClass = e.getClass();
                throw eClass.cast(e);
            }
        } // Local project exceptions
        catch (EmptyRequestBodyException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        } catch (InvalidXfaFormException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid XFA form.").build();
        } catch (InvalidReturnDataFormatException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid format parameter: Must be 'json' or 'xml'.").build();
        }
        // Dependency and built-in exceptions
        catch (JsonProcessingException e) {
            // This is above IOException because JsonProcessingExceptions inherits from it.
            // This may be converted to a generic error message, since it's not a user-correctable issue.
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Converting XML to JSON failed.").build();
        } catch (IllegalArgumentException e) {
            //TODO Incorrect message for this exception type?
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Body not valid base64.").build();
        }  catch (Exception e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        }
    }

    /**
     * The built-in <code>Supplier</code> does not throw checked exceptions, so this provides that capability
     * for use in lambda expressions.
     * @param <T> The supplier return type.
     */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
