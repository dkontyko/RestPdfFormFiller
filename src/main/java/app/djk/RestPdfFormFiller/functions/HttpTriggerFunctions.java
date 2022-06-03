package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import javax.xml.transform.TransformerException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Azure Functions with HTTP Trigger.
 */
public class HttpTriggerFunctions {

    @FunctionName("GetBlank4187Data")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {
        context.getLogger().info("Java HTTP trigger processed a request.");

        // Parse query parameter
        final String query = request.getQueryParameters().get("formURL");
        final String formURL = request.getBody().orElse(query);

        if (formURL == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Please pass a formURL on the query string or in the request body").build();
        }

        try (var inputStream = new BufferedInputStream(new URL(formURL).openStream())) {
            final var datasetsString = RestPdfApi.get4187DatasetNodeAsString(inputStream);
            return request.createResponseBuilder(HttpStatus.OK).body(datasetsString).build();
        } catch (MalformedURLException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("formURL invalid.").build();
        } catch (IOException e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        } catch (TransformerException e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("DOM Transform failed.").build();
        }
    }

    /**
     * Azure Function that receives a Base64-encoded PDF file and returns the pretty-printed XFA form field data.
     * @param request Azure Function parameter that expects a Base64-encoded binary PDF.
     * @param context Azure Function parameter.
     * @return An HTTP Response indicating the result of the request. If successful, the body will contain the
     * XFA form field data.
     */
    @FunctionName("Get4187Data")
    public HttpResponseMessage get4187Data(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        // Checking if a body was received in the HTTP request.
        if(request.getBody().isEmpty()) {
            context.getLogger().log(Level.SEVERE, "No content supplied in body.");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("No content supplied in body.").build();
        }

        // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
        var requestBytes = Base64.getDecoder().decode(request.getBody());
        context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

        try {
            final var datasetsString = RestPdfApi.get4187DatasetNodeAsString(requestBytes);
            return request.createResponseBuilder(HttpStatus.OK).body(datasetsString).build();
        } catch (IOException e) { // Catching errors from the RestPdfApi methods.
            context.getLogger().log(Level.SEVERE, "Unable to create InputStream.");
            context.getLogger().log(Level.SEVERE, e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Request failed.").build();
        } catch (TransformerException e) {
            context.getLogger().log(Level.SEVERE, e.toString());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("DOM Transform failed.").build();
        }
    }
}
