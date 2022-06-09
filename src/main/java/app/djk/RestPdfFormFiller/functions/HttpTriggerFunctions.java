package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;

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
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        // Checking if a body was received in the HTTP request.
        if(request.getBody().isEmpty()) {
            context.getLogger().warning("No content supplied in body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        }

        // Desired format of the returned form data.
        final var returnDataFormat = request.getQueryParameters().get("format");

        if(!(Objects.equals(returnDataFormat, "json")) && !(Objects.equals(returnDataFormat, "xml"))) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid format parameter: Must be 'json' or 'xml'.").build();
        }

        // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
        var requestBytes = Base64.getDecoder().decode(request.getBody());
        context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

        try {
            var datasetsString = RestPdfApi.get4187DatasetNodeAsString(requestBytes);

            if(returnDataFormat.equals("json")) {
                datasetsString = RestPdfApi.convertXmlToJsonString(datasetsString);
            }
            return request.createResponseBuilder(HttpStatus.OK).body(datasetsString).build();

        } catch (JsonProcessingException e) {
            // This is above IOException because JsonProcessingExceptions inherits from it.
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Converting XML to JSON failed.").build();

        } catch (IOException e) {
            context.getLogger().warning("Unable to create InputStream.");
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();

        } catch (TransformerException e) {
            context.getLogger().warning("DOM Transform failed.");
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        }
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
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Converting XML to JSON failed.").build();

        } catch (IOException e) {
            context.getLogger().warning("Unable to create InputStream.");
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();

        } catch (TransformerException e) {
            context.getLogger().warning("DOM Transform failed.");
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
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        // Checking if a body was received in the HTTP request.
        if(request.getBody().isEmpty()) {
            context.getLogger().warning("No content supplied in body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        }

        try {
            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            var requestBytes = Base64.getDecoder().decode(request.getBody());
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            if(!RestPdfApi.isXfaForm(requestBytes)) {
                throw new IOException();
            }

            var sessionId = FileSessions.storeFile(requestBytes);

            return request.createResponseBuilder(HttpStatus.CREATED).header("sessionId", sessionId).build();
        } catch (IllegalArgumentException e) {
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Body not valid base64.").build();
        } catch (IOException e) {
            context.getLogger().warning(e.toString());
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid PDF forms.").build();
        }
    }

    /**
     * Takes a session ID in the header and a JSON string in the body of the form field data. Fills the
     * XFA PDF associated with the session with the submitted data. Returns the PDF in binary.
     * @param request
     * @param context
     * @return
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

        throw new RuntimeException("Not implemented");

    }

    private HttpResponseMessage errorHandler(final HttpRequestMessage<?> request, final ExecutionContext context, Exception ex) {
        // error handling will probably be refactored here.
        throw new RuntimeException("Not yet implemented.");
    }
}
