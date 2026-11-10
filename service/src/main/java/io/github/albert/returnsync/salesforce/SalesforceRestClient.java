package io.github.albert.returnsync.salesforce;

import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * REST upsert by External ID ("Insert or Update (Upsert) a Record Using an External ID"), with
 * {@code updateOnly=true} (API 61.0+): the request never creates a record when the External ID is unknown.
 * JDK HttpClient instead of Spring RestClient: zero extra dependencies, no web starter.
 */
@Component
public class SalesforceRestClient {

    private final JwtBearerTokenService tokens;
    private final JsonMapper json;
    private final String apiVersion;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public SalesforceRestClient(JwtBearerTokenService tokens, JsonMapper json,
                                @Value("${salesforce.api-version}") String apiVersion) {
        this.tokens = tokens;
        this.json = json;
        this.apiVersion = apiVersion;
    }

    public void updateStatus(String externalId, String status) {
        for (int authAttempt = 0; ; authAttempt++) {
            SalesforceToken token = tokens.get();
            HttpResponse<String> resp = send(token, externalId, status);
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return;            // 200 update (API 46.0+), 201 created, 204 legacy
            if (code == 401 && authAttempt == 0) {             // session expired: sign a new JWT once
                tokens.invalidate(token);
                continue;
            }
            String body = resp.body();
            // A second 401 with a fresh token is a credentials problem, not the message's fault:
            // keep the replay ID where it is instead of dead-lettering every event during an auth outage.
            if (code == 401 || code == 429 || code >= 500 || body.contains("REQUEST_LIMIT_EXCEEDED")) {
                throw new TransientSalesforceException("Salesforce " + code + ": " + body);
            }
            throw new PermanentSalesforceException("Salesforce " + code + ": " + body);
        }
    }

    private HttpResponse<String> send(SalesforceToken token, String externalId, String status) {
        try {
            URI uri = URI.create(token.instanceUrl() + "/services/data/v" + apiVersion
                    + "/sobjects/Return_Request__c/External_Id__c/"
                    + pathSegment(externalId) + "?updateOnly=true");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token.accessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(Map.of("Status__c", status))))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TransientSalesforceException("I/O error calling Salesforce", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientSalesforceException("interrupted", e);
        }
    }

    /** URLEncoder is for forms: it encodes a space as '+', which is wrong in a path segment. */
    static String pathSegment(String value) {
        return URLEncoder.encode(value, UTF_8).replace("+", "%20");
    }
}
