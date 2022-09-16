package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidSessionIdException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * Azure Functions with HTTP Trigger.
 */
public class HttpTriggerFunctions {

    //TODO clean up
    private static final URL baseFunctionUrl;

    static {
        try {
            baseFunctionUrl = new URL(System.getenv("WebUrl"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

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

            if(!RestPdfApi.FORM_DATA_FORMATS.contains(returnDataFormat)) {
                throw new InvalidReturnDataFormatException();
            }

            // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
            final var requestBytes = Base64.getDecoder().decode(requestBody);
            context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

            var datasetsString = RestPdfApi.getXfaDatasetNodeAsString(requestBytes);
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
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () ->{
            if(request.getBody().isEmpty()) {
                // If no file sent in body, then retrieve file from SPO.

                //TODO remove debug code
                // Testing token headers
                context.getLogger().info("Printing headers...");
                request.getHeaders().forEach((key, value) -> context.getLogger().info(key + ": " + value));

                if(request.getHeaders().get("X-MS-TOKEN-AAD-ID-TOKEN") != null) {
                    context.getLogger().info("AAD ID token is present.");
                }
                if(request.getHeaders().get("X-MS-TOKEN-AAD-ACCESS-TOKEN") != null) {
                    context.getLogger().info("AAD access token is present.");
                }
                context.getLogger().info("If Expires-On present, it will be here: " + request.getHeaders().getOrDefault("X-MS-TOKEN-AAD-EXPIRES-ON", "no expires-on key found"));


                // validating query input parameters by casting them to their requisite types.
                final var siteID = validateSiteID(request.getQueryParameters().getOrDefault("siteID", ""));
                final var listID = UUID.fromString(request.getQueryParameters().getOrDefault("listID", ""));
                final var itemID = Integer.parseInt(request.getQueryParameters().getOrDefault("itemID", ""));

                context.getLogger().info("Site ID: " + siteID);
                context.getLogger().info("List ID: " + listID);
                context.getLogger().info("Item ID: "+ itemID);

                final var defaultCredential = (new DefaultAzureCredentialBuilder()).build();

                //final var tokenCredential = new TokenCredentialAuthProvider(defaultCredential);

                final var tokenURL = new URL(baseFunctionUrl, "/.auth/me");
                final var tokenCredential = new TokenCredentialAuthProvider(defaultCredential);
                final GraphServiceClient<Request> graphClient = GraphServiceClient
                        .builder()
                        .authenticationProvider(tokenCredential)
                        .buildClient();

                final var authToken = tokenCredential.getAuthorizationTokenAsync(tokenURL);

                final var result = graphClient
                        .sites(siteID)
                        .lists(listID.toString())
                        .items(Integer.toString(itemID))
                        .driveItem()
                        .content()
                        .buildRequest();

                //TODO below statement is debug code
                if(request.getHeaders().get("Authorization") != null)
                    result.addHeader("Authorization", authToken.get());

                final var fileStream = result.get();

                Objects.requireNonNull(fileStream, "Could not retrieve file stream.");
                final var dataSchema = RestPdfApi.generateJsonSchema(RestPdfApi.getXfaDatasetNodeAsString(fileStream));
                return request.createResponseBuilder(HttpStatus.OK).body(dataSchema).build();
            } else {
                final var requestBody = request.getBody().get();
                // The function expects the PDF to be encoded in Base64 for safe transit over the internet.
                var requestBytes = Base64.getDecoder().decode(requestBody);
                context.getLogger().info("Request length (number of bytes): " + requestBytes.length);

                var dataSchema = RestPdfApi.generateJsonSchema(RestPdfApi.getXfaDatasetNodeAsString(requestBytes));
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
            HttpRequestMessage<String> request,
            final ExecutionContext context) {


        // get list ID and item ID from URL query
        // get PDF from SPO
        // fill with body json from request
        // return PDF in response body (don't edit file)


        return request.createResponseBuilder(HttpStatus.NOT_IMPLEMENTED).build();
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

    /**
     * Basic input validation to ensure the siteID doesn't have obvious attempts at code injection.
     * Site IDs should (probably) not be more than 200 characters, not contain slashes, and contain
     * exactly 2 commas.
     * @param siteID The site ID to validate.
     * @return The value as <code>siteID</code>, if validation checks passed.
     */
    private static String validateSiteID(final String siteID) {
        if(siteID.length() > 200 ||
            siteID.chars().filter(ch ->ch == ',').count() != 2 ||
            siteID.indexOf('/') != -1) {
            throw new IllegalArgumentException("Invalid site ID.");
        }
        return siteID;
    }
}
