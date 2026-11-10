package io.github.albert.returnsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "salesforce")
public record SalesforceProperties(
        String loginUrl,        // https://login.salesforce.com or My Domain
        String audience,        // JWT aud: https://login.salesforce.com
        String clientId,        // Consumer Key of the External Client App
        String username,        // integration user (JWT sub)
        Path privateKeyPath,    // PKCS#8 PEM, mounted into the container read-only
        String apiVersion,      // "68.0"
        String pubsubHost,      // api.pubsub.salesforce.com
        int pubsubPort,         // 7443
        String topic,           // /event/Return_Requested__e
        int batchSize) {        // num_requested per FetchRequest

    /** Pub/Sub API rejects num_requested above 100 (Subscribe RPC docs). */
    public static final int MAX_NUM_REQUESTED = 100;

    public SalesforceProperties {
        if (batchSize < 1 || batchSize > MAX_NUM_REQUESTED) {
            throw new IllegalArgumentException(
                    "salesforce.batch-size must be 1.." + MAX_NUM_REQUESTED + ", got " + batchSize);
        }
    }
}
