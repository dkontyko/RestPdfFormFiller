package app.djk.RestPdfFormFiller.functions;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.Base64;

public class FileSessions {

    static String generateSessionId() {
        var bytes = new byte[32];
        (new SecureRandom()).nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String storeFile(byte[] file) {
        final var sessionId = generateSessionId();
        final var defaultAzureCredential = (new DefaultAzureCredentialBuilder()).build();
        final var filename = java.util.UUID.randomUUID() + ".pdf";

        var blobClient = getBlobClientHelper(filename, defaultAzureCredential);
        blobClient.upload(new ByteArrayInputStream(file), file.length);

        var tableClient = getTableClientHelper(defaultAzureCredential);

        // Using session ID as partition key and filename as row key.
        tableClient.createEntity(
                new TableEntity(sessionId, filename)
        );

        return sessionId;
    }

    private static BlobClient getBlobClientHelper(String filename, TokenCredential credential) {
        var fillSessionEndpoint = System.getenv("fillSessionEndpoint");
        var blobSvcClient = new BlobServiceClientBuilder()
                .endpoint(System.getenv("fillSessionEndpoint"))
                .credential(credential)
                .buildClient();

        var containerClient = blobSvcClient.getBlobContainerClient(
                System.getenv("fillSessionContainer"));

        return containerClient.getBlobClient(filename);
    }

    private static TableClient getTableClientHelper(TokenCredential credential) {
        return new TableClientBuilder()
                .endpoint(System.getenv("fillSessionTableEndpoint"))
                .credential(credential)
                .tableName(System.getenv("fillSessionTable"))
                .buildClient();
    }
}
