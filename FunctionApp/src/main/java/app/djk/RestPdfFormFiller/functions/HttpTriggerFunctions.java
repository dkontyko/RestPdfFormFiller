package app.djk.RestPdfFormFiller.functions;

import app.djk.RestPdfFormFiller.Pdf.DataFormatter;
import app.djk.RestPdfFormFiller.Pdf.RestPdfApi;
import app.djk.RestPdfFormFiller.projectExceptions.EmptyRequestBodyException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidReturnDataFormatException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidSessionIdException;
import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.OnBehalfOfCredentialBuilder;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

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

                final var fileStream = getFileInputStreamFromSpo(request);
                final var dataSchema = DataFormatter.generateJsonSchema(RestPdfApi.getXfaDatasetNodeAsString(fileStream));
                return request.createResponseBuilder(HttpStatus.OK).body(dataSchema).build();
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


        // get PDF from SPO
        // get form content from request body
        // fill form with content
        // return PDF in response body (don't edit file)

        return errorHandler(request, context, () -> {

            final var fileStream = getFileInputStreamFromSpo(request);
            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);

            final var filledFormStream = RestPdfApi.fillXfaForm(fileStream, requestBody);
            final var base64EncodedForm = Base64.getEncoder().encode(filledFormStream.toString().getBytes());

            return request.createResponseBuilder(HttpStatus.OK).body(base64EncodedForm).build();
            //return request.createResponseBuilder(HttpStatus.NOT_IMPLEMENTED).build();
        });
    }

    @FunctionName("AuthNTest")
    public HttpResponseMessage authNTest(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        return errorHandler(request, context, () -> {
//            final var fileStream = getFileInputStreamFromSpo(request);
//            final var requestBody = request.getBody().orElseThrow(EmptyRequestBodyException::new);
//
//            final var filledFormStream = RestPdfApi.fillXfaForm(fileStream, requestBody);
//            final var base64EncodedForm = Base64.getEncoder().encode(filledFormStream.toString().getBytes());
//
//            return request.createResponseBuilder(HttpStatus.OK).body(base64EncodedForm).build();
//            //return request.createResponseBuilder(HttpStatus.NOT_IMPLEMENTED).build();

            var nothing = getAuthTokenOverHttp(request, context);
            return request.createResponseBuilder(HttpStatus.OK).body(nothing).build();
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
        catch (ApiException e) {
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

    /**
     * Basic input validation to ensure the siteID doesn't have obvious attempts at code injection.
     * Site IDs should (probably) not be more than 200 characters, not contain slashes, and contain
     * exactly 2 commas.
     *
     * @param siteID The site ID to validate.
     * @return The value as <code>siteID</code>, if validation checks passed.
     */
    private static String validateSiteID(final String siteID) {
        if (siteID.length() > 200 ||
                siteID.chars().filter(ch -> ch == ',').count() != 2 ||
                siteID.indexOf('/') != -1) {
            throw new IllegalArgumentException("Invalid site ID.");
        }
        return siteID;
    }

    /**
     * Returns the appropriate token credential auth provider based on whether an authorization header was included
     * in the HTTP request. If the header was included, this will return an on-behalf-of credential that grants access
     * to the default Graph APIs for the registered application using the incoming user's identity. If no header
     * was included, then this returns the default Azure credential.
     *
     * @param request The HTTP request received by the function.
     * @param <T>     Not used; a generic reference to the HttpRequestMessage's contained type.
     * @return A token credential auth provider generated from either the default Azure credential or
     * an on-behalf-of credential.
     */
    private static <T> TokenCredential getTokenCredential(final HttpRequestMessage<T> request) {
        final var authHeader = request.getHeaders().get("authorization");

        if (authHeader == null) {
            // Right now this is limited to local test cases
            return new DefaultAzureCredentialBuilder().build();
        } else {
            final var incomingAccessToken = authHeader.substring(7); // removing "Bearer" prefix and space

            return new OnBehalfOfCredentialBuilder()
                    .tenantId(System.getenv("tenantId"))
                    .clientId(System.getenv("clientId"))
                    .clientSecret(System.getenv("clientSecret"))
                    .userAssertion(incomingAccessToken)
                    .additionallyAllowedTenants(Collections.singletonList("*"))
                    .build();

        }
    }

    private static <T> InputStream getFileInputStreamFromSpo(final HttpRequestMessage<T> request) {
        // validating query input parameters by casting them to their requisite types.
        final var siteID = validateSiteID(request.getQueryParameters().getOrDefault("siteID", ""));
        final var listID = UUID.fromString(request.getQueryParameters().getOrDefault("listID", ""));
        final var itemID = Integer.parseInt(request.getQueryParameters().getOrDefault("itemID", ""));

        final var tokenCredential = getTokenCredential(request);
        final GraphServiceClient graphClient = new GraphServiceClient(
                tokenCredential,
                "https://graph.microsoft.com/.default"
        );

        final var result = graphClient
                .sites()
                .bySiteId(siteID)
                .lists()
                .byListId(listID.toString())
                .items()
                .byListItemId(Integer.toString(itemID))
                .driveItem()
                .content();

        final var fileStream = result.get();

        Objects.requireNonNull(fileStream, "Could not retrieve file stream.");

        return fileStream;
    }

    private static <T> String getAuthTokenOverHttp(final HttpRequestMessage<T> request, final ExecutionContext context) {
        final var authHeader = request.getHeaders().get("authorization");

        if (authHeader == null) {
            throw new IllegalArgumentException("No authorization header provided.");
        }

        final var incomingToken = authHeader.substring(7); // removing "Bearer" prefix and space
        context.getLogger().info("Incoming token: " + incomingToken);

        final var client = new OkHttpClient();

        final var formBody = new FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("client_id", System.getenv("clientId"))
                .add("client_secret", System.getenv("clientSecret"))
                .add("assertion", incomingToken)
                .add("scope", "https://graph.microsoft.com/.default")
                .add("requested_token_use", "on_behalf_of")
                .build();
        context.getLogger().info("Form body: " + formBody.toString());

        final var outgoingRequest = new Request.Builder()
                .url("https://login.microsoftonline.com/" + System.getenv("tenantId") + "/oauth2/v2.0/token")
                .post(formBody)
                .build();

        try (final var response = client.newCall(outgoingRequest).execute()) {
            context.getLogger().info("Executed outgoing request.");
            final var responseString = Objects.requireNonNull(response.body()).string();
            System.out.println(responseString);
            return "test";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
