package io.github.albert.returnsync.pubsub;

import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Executor;

/**
 * Adds the Pub/Sub API auth headers to every call. The official sample (utility/APISessionCredentials.java)
 * uses "accessToken" / "instanceUrl" / "tenantId"; gRPC metadata keys are case-insensitive and sent lowercase.
 * The token is read on every call, so a reconnect after a 401 automatically picks up the fresh one.
 */
final class SalesforceCallCredentials extends CallCredentials {

    static final Metadata.Key<String> ACCESS_TOKEN =
            Metadata.Key.of("accesstoken", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> INSTANCE_URL =
            Metadata.Key.of("instanceurl", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> TENANT_ID =
            Metadata.Key.of("tenantid", Metadata.ASCII_STRING_MARSHALLER);

    private final JwtBearerTokenService tokens;

    SalesforceCallCredentials(JwtBearerTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public void applyRequestMetadata(RequestInfo info, Executor appExecutor, MetadataApplier applier) {
        appExecutor.execute(() -> {   // the token HTTP call must not run on a network thread
            try {
                SalesforceToken t = tokens.get();
                Metadata headers = new Metadata();
                headers.put(ACCESS_TOKEN, t.accessToken());
                headers.put(INSTANCE_URL, t.instanceUrl());
                headers.put(TENANT_ID, t.orgId());
                applier.apply(headers);
            } catch (Exception e) {
                applier.fail(Status.UNAUTHENTICATED.withDescription("Cannot get Salesforce token").withCause(e));
            }
        });
    }
}
