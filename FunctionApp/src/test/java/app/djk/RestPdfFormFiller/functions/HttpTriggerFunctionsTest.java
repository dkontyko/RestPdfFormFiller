package app.djk.RestPdfFormFiller.functions;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpTriggerFunctionsTest {

    @Test
    void getXfaDataReturnsBadRequestWhenBodyMissing() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.<String>empty(), Map.of("format", "json"));

        final var actualResponse = function.getXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("No content supplied in body.");
    }

    @Test
    void getXfaDataReturnsBadRequestWhenFormatIsInvalid() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.of("dGVzdA=="), Map.of("format", "yaml"));

        final var actualResponse = function.getXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid format parameter: Must be 'json' or 'xml'.");
    }

    @Test
    void getXfaDataReturnsBadRequestWhenFormatIsMissing() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.of("dGVzdA=="), Map.of());

        final var actualResponse = function.getXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid format parameter: Must be 'json' or 'xml'.");
    }

    @Test
    void getXfaSchemaReturnsBadRequestWhenBodyMissing() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.<String>empty(), Map.of());

        final var actualResponse = function.getXfaSchema(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("No content supplied in body.");
    }

    @Test
    void getXfaSchemaReturnsBadRequestWhenBodyIsNotBase64() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.of("not base64"), Map.of());

        final var actualResponse = function.getXfaSchema(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid argument in request.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenBodyMissing() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.<String>empty(), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("No content supplied in body.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenRequestBodyIsNotJson() {
        final var function = new HttpTriggerFunctions();
        final var responseMocks = setupResponseMocks(Optional.of("this is not json"), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request body must be valid JSON.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenValidateOnlyIsTrueAndTemplateIsInvalidPdf() {
        final var function = new HttpTriggerFunctions();
        final var requestBody = "{\"templateBase64\":\"dGVzdA==\",\"formData\":{\"data\":{}},\"validateOnly\":true}";
        final var responseMocks = setupResponseMocks(Optional.of(requestBody), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid XFA form.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenValidateOnlyIsTrueAndTemplateBase64IsNotDecodable() {
        final var function = new HttpTriggerFunctions();
        final var requestBody = "{\"templateBase64\":\"!!!!\",\"formData\":{\"data\":{}},\"validateOnly\":true}";
        final var responseMocks = setupResponseMocks(Optional.of(requestBody), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid argument in request.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenTemplateBase64IsNotString() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":123,\"formData\":{\"data\":{}}}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'templateBase64' must be a non-empty string.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenFormDataIsNotObject() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"dGVzdA==\",\"formData\":\"not-an-object\"}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'formData' must be a JSON object.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenFormDataDataIsNotObject() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"dGVzdA==\",\"formData\":{\"data\":\"x\"}}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'formData' must contain only a 'data' object.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenFormDataContractIsInvalid() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"dGVzdA==\",\"formData\":{\"value\":\"x\"}}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'formData' must contain only a 'data' object.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenTemplateBase64IsBlank() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"   \",\"formData\":{\"data\":{}}}";

        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'templateBase64' must be a non-empty string.");
    }

    @Test
    void fillXfaDataReturnsGenericMessageForNonAllowlistedIllegalArgumentException() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"!!!!\",\"formData\":{\"data\":{}}}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Invalid argument in request.");
    }

    @Test
    void fillXfaDataReturnsBadRequestWhenFormDataContainsUnexpectedKeys() {
        final var function = new HttpTriggerFunctions();
        final var invalidPayload = "{\"templateBase64\":\"dGVzdA==\",\"formData\":{\"data\":{},\"extra\":{}}}";
        final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

        final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

        assertSame(responseMocks.response(), actualResponse);
        verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseMocks.builder()).body("Request field 'formData' must contain only a 'data' object.");
    }

        @Test
        void fillXfaDataReturnsBadRequestWhenPayloadModeIsInvalid() {
                final var function = new HttpTriggerFunctions();
                final var invalidPayload = """
                                {
                                    "templateBase64": "dGVzdA==",
                                    "formData": {"data": {}},
                                    "payloadMode": "unknown"
                                }
                                """;
                final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

                final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

                assertSame(responseMocks.response(), actualResponse);
                verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
                verify(responseMocks.builder()).body("Request field 'payloadMode' must be one of the following strings: partial, complete.");
        }

        @Test
        void fillXfaDataReturnsBadRequestWhenWriteModeIsInvalid() {
                final var function = new HttpTriggerFunctions();
                final var invalidPayload = """
                                {
                                    "templateBase64": "dGVzdA==",
                                    "formData": {"data": {}},
                                    "writeMode": "mergeMaybe"
                                }
                                """;
                final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

                final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

                assertSame(responseMocks.response(), actualResponse);
                verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
                verify(responseMocks.builder()).body("Request field 'writeMode' must be one of the following strings: overwrite, ifEmpty, failOnConflict.");
        }

        @Test
        void fillXfaDataReturnsBadRequestWhenValidateOnlyIsNotBoolean() {
                final var function = new HttpTriggerFunctions();
                final var invalidPayload = """
                                {
                                    "templateBase64": "dGVzdA==",
                                    "formData": {"data": {}},
                                    "validateOnly": "yes"
                                }
                                """;
                final var responseMocks = setupResponseMocks(Optional.of(invalidPayload), Map.of());

                final var actualResponse = function.fillXfaData(responseMocks.request(), responseMocks.context());

                assertSame(responseMocks.response(), actualResponse);
                verify(responseMocks.request()).createResponseBuilder(HttpStatus.BAD_REQUEST);
                verify(responseMocks.builder()).body("Request field 'validateOnly' must be a boolean.");
        }

    private static ResponseMocks setupResponseMocks(
            final Optional<String> body,
            final Map<String, String> queryParameters) {

        @SuppressWarnings("unchecked")
        final var request = (HttpRequestMessage<Optional<String>>) mock(HttpRequestMessage.class);
        final var context = mock(ExecutionContext.class);
        final var builder = mock(HttpResponseMessage.Builder.class);
        final var response = mock(HttpResponseMessage.class);

        when(context.getLogger()).thenReturn(Logger.getLogger("HttpTriggerFunctionsTest"));
        when(request.getBody()).thenReturn(body);
        when(request.getQueryParameters()).thenReturn(queryParameters);
        when(request.getHeaders()).thenReturn(Map.of());

        when(request.createResponseBuilder(any(HttpStatusType.class))).thenReturn(builder);
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(builder);
        when(builder.body(any())).thenReturn(builder);
        when(builder.header(any(), any())).thenReturn(builder);
        when(builder.status(eq(HttpStatus.BAD_REQUEST))).thenReturn(builder);
        when(builder.status(any(HttpStatusType.class))).thenReturn(builder);
        when(builder.build()).thenReturn(response);

        return new ResponseMocks(request, context, builder, response);
    }

    private record ResponseMocks(
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext context,
            HttpResponseMessage.Builder builder,
            HttpResponseMessage response) {
    }
}
