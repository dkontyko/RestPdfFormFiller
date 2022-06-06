package app.djk.RestPdfFormFiller.functions;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class FileSessions {

    private static String generateSessionId() {
        var bytes = new byte[32];
        (new SecureRandom()).nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String getHashedSessionId(String sessionId) {
        //TODO clean this up
        try {
            final var messageDigest = MessageDigest.getInstance("SHA-256");

            return Base64.getEncoder().encodeToString(
                    messageDigest.digest(
                            sessionId.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch(NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param file The PDF file to be stored.
     * @return A base64-encoded session ID string.
     */
    static String storeFile(byte[] file) {
        final var sessionId = generateSessionId();
        final var defaultAzureCredential = (new DefaultAzureCredentialBuilder()).build();
        final var filename = java.util.UUID.randomUUID() + ".pdf";

        var blobClient = getBlobClientHelper(filename, defaultAzureCredential);
        blobClient.upload(new ByteArrayInputStream(file), file.length);

        var tableClient = getTableClientHelper(defaultAzureCredential);

        /*
         Using filename as row key.
         Lol, I forgot that base64 includes slashes. No longer using session
         key as partition key. For now, just using a single partition. May need to
         think about this more. The most common operations will be searching session
         IDs and inserting/deleting them.
        */
        tableClient.createEntity(
                new TableEntity("1", filename)
                        .addProperty("sessionId", getHashedSessionId(sessionId))
        );

        return sessionId;
    }

    private static BlobClient getBlobClientHelper(String filename, TokenCredential credential) {
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
