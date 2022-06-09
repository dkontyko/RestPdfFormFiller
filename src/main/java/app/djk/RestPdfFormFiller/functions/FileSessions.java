package app.djk.RestPdfFormFiller.functions;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class FileSessions {

    private static String generateSessionId() {
        var bytes = new byte[64];
        (new SecureRandom()).nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Hashes the session ID with SHA-256 and returns the base64 encoding
     * of that hash.
     * This is the string that will be stored in the Azure table.
     *
     * @param sessionId The base64 encoding of the session ID.
     * @return The base64 encoding of the hashed session ID.
     */
    private static String getHashedSessionId(String sessionId) {
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
     * Stores the given file in Azure blob storage and creates a cryptographically secure session ID
     * for retrieval of the file in subsequent operations.
     *
     * @param file The PDF file to be stored.
     * @return A base64-encoded session ID string.
     */
    static String storeFile(byte[] file) {
        final var sessionId = generateSessionId();
        final var defaultAzureCredential = (new DefaultAzureCredentialBuilder()).build();
        final var filename = java.util.UUID.randomUUID() + ".pdf";

        final var blobClient = getBlobClientHelper(filename, defaultAzureCredential);
        blobClient.upload(new ByteArrayInputStream(file), file.length);

        var tableClient = getTableClientHelper();

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

    static byte[] retrieveFile(String sessionId) {
        final var hashedSessionId = getHashedSessionId(sessionId);
        final var defaultAzureCredential = (new DefaultAzureCredentialBuilder()).build();
        final var tableClient = getTableClientHelper();

        //----------------------------------------------------------------------------------
        // Table entity retrieval

        final var options = new ListEntitiesOptions()
                .setFilter(String.format("PartitionKey eq '1' and sessionId eq '%s'", hashedSessionId));

        final var entities = tableClient.listEntities(options, null, null).stream();

        // Sanity/integrity check
        if(entities.count() != 1) {
            //TODO make this more graceful.
            throw new RuntimeException("Invalid number of table entities retrieved.");
        }

        //----------------------------------------------------------------------------------
        // File manipulation

        final var filename = entities.findFirst().orElseThrow().getRowKey();
        final var blobClient = getBlobClientHelper(filename, defaultAzureCredential);
        var byteArrayOutputStream = new ByteArrayOutputStream();

        blobClient.download(byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Creates an Azure blob client within the preset storage account for the
     * given filename. The storage account is set in the environment variables.
     *
     * @param filename The filename that the blob client will be used for.
     * @param credential The default Azure credential object to authenticate to the
     *                   storage account with.
     * @return A blob client that can be used for the given filename.
     */
    private static BlobClient getBlobClientHelper(String filename, TokenCredential credential) {
        final var blobSvcClient = new BlobServiceClientBuilder()
                .endpoint(System.getenv("fillSessionEndpoint"))
                .credential(credential)
                .buildClient();

        final var containerClient = blobSvcClient.getBlobContainerClient(
                System.getenv("fillSessionContainer"));

        return containerClient.getBlobClient(filename);
    }

    private static TableClient getTableClientHelper() {
        return new TableClientBuilder()
                .connectionString(System.getenv("fillSessionTableConnectionString"))
                .tableName(System.getenv("fillSessionTable"))
                .buildClient();
    }
}
