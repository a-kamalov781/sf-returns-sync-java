package io.github.albert.returnsync.salesforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Status write-back as an sObject Collections upsert by External ID:
 * {@code PATCH /services/data/vXX.X/composite/sobjects/Return_Request__c/External_Id__c?updateOnly=true}, up to
 * 200 records per call, {@code allOrNone=false}. {@code updateOnly=true} is honoured per record: an unknown
 * External ID comes back as {@code NOT_FOUND} and nothing is created (checked against a live org, API 68.0).
 * One call per FetchResponse instead of one per event: the live load test showed ~0.3 s per call, so a burst of
 * 100 single PATCHes took ~34 s.
 * <p>
 * JDK HttpClient instead of Spring RestClient: zero extra dependencies, no web starter.
 */
@Component
public class SalesforceRestClient {

    /** sObject Collections limit. */
    public static final int MAX_RECORDS_PER_CALL = 200;
    /** Per-record errors that a retry can fix: retry the whole call (the upsert itself is idempotent). */
    static final Set<String> TRANSIENT_RECORD_ERRORS = Set.of("UNABLE_TO_LOCK_ROW", "REQUEST_LIMIT_EXCEEDED");
    /**
     * Errors that mean "this user may not do this" rather than "this record is bad". When every record of a call
     * fails with one of them it is a configuration problem (found live: a missing field permission dead-lettered
     * a whole batch), so the call is treated as transient: the replay ID stays, events wait on the bus (72 h)
     * until an admin fixes the permission. A single record with such an error, next to successes, is
     * record-specific and is dead-lettered as usual.
     */
    static final Set<String> CONFIG_ERRORS = Set.of("INVALID_FIELD_FOR_INSERT_UPDATE", "INSUFFICIENT_ACCESS_OR_READONLY",
            "INSUFFICIENT_ACCESS_ON_CROSS_REFERENCE_ENTITY", "INVALID_SESSION_ID", "API_DISABLED_FOR_ORG");

    public record StatusUpdate(String externalId, String status) {}

    /** {@code errorCode}/{@code message} are null on success. */
    public record UpdateResult(String externalId, boolean success, String errorCode, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordResult(String id, boolean success, List<RecordError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordError(String statusCode, String message) {}

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

    /**
     * Results in the order of {@code updates}.
     *
     * @throws TransientSalesforceException 401 after a token refresh, 429, 5xx, I/O, REQUEST_LIMIT_EXCEEDED, or a
     *                                      per-record error a retry can fix
     * @throws PermanentSalesforceException any other failure of the whole call
     */
    public List<UpdateResult> updateStatuses(List<StatusUpdate> updates) {
        List<UpdateResult> all = new ArrayList<>(updates.size());
        for (int from = 0; from < updates.size(); from += MAX_RECORDS_PER_CALL) {
            all.addAll(upsertChunk(updates.subList(from, Math.min(updates.size(), from + MAX_RECORDS_PER_CALL))));
        }
        return all;
    }

    private List<UpdateResult> upsertChunk(List<StatusUpdate> chunk) {
        String body = requestBody(chunk);
        for (int authAttempt = 0; ; authAttempt++) {
            SalesforceToken token = tokens.get();
            HttpResponse<String> resp = send(token, body);
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return results(chunk, resp.body());
            if (code == 401 && authAttempt == 0) {             // session expired: sign a new JWT once
                tokens.invalidate(token);
                continue;
            }
            String text = resp.body();
            // A second 401 with a fresh token is a credentials problem, not the messages' fault:
            // keep the replay ID where it is instead of dead-lettering every event during an auth outage.
            if (code == 401 || code == 429 || code >= 500 || text.contains("REQUEST_LIMIT_EXCEEDED")) {
                throw new TransientSalesforceException("Salesforce " + code + ": " + text);
            }
            throw new PermanentSalesforceException("Salesforce " + code + ": " + text);
        }
    }

    private String requestBody(List<StatusUpdate> chunk) {
        List<Map<String, Object>> records = new ArrayList<>(chunk.size());
        for (StatusUpdate u : chunk) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("attributes", Map.of("type", "Return_Request__c"));
            r.put("External_Id__c", u.externalId());
            r.put("Status__c", u.status());
            records.add(r);
        }
        return json.writeValueAsString(Map.of("allOrNone", false, "records", records));
    }

    private List<UpdateResult> results(List<StatusUpdate> chunk, String body) {
        RecordResult[] parsed = json.readValue(body, RecordResult[].class);
        if (parsed.length != chunk.size()) {
            throw new PermanentSalesforceException(
                    "Salesforce returned " + parsed.length + " results for " + chunk.size() + " records: " + body);
        }
        List<UpdateResult> out = new ArrayList<>(chunk.size());
        for (int i = 0; i < parsed.length; i++) {
            RecordResult r = parsed[i];
            String externalId = chunk.get(i).externalId();
            if (r.success()) {
                out.add(new UpdateResult(externalId, true, null, null));
                continue;
            }
            RecordError err = r.errors() == null || r.errors().isEmpty()
                    ? new RecordError("UNKNOWN_ERROR", "no error details") : r.errors().getFirst();
            if (TRANSIENT_RECORD_ERRORS.contains(err.statusCode())) {
                throw new TransientSalesforceException("Salesforce record " + externalId + ": "
                        + err.statusCode() + " " + err.message());
            }
            out.add(new UpdateResult(externalId, false, err.statusCode(), err.message()));
        }
        boolean allConfigErrors = out.stream().noneMatch(UpdateResult::success)
                && out.stream().allMatch(r -> CONFIG_ERRORS.contains(r.errorCode()));
        if (allConfigErrors) {
            throw new TransientSalesforceException("Salesforce rejected all " + out.size() + " records with "
                    + out.getFirst().errorCode() + " (permission/configuration, not the data): "
                    + out.getFirst().message());
        }
        return out;
    }

    private HttpResponse<String> send(SalesforceToken token, String body) {
        try {
            URI uri = URI.create(token.instanceUrl() + "/services/data/v" + apiVersion
                    + "/composite/sobjects/Return_Request__c/External_Id__c?updateOnly=true");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token.accessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TransientSalesforceException("I/O error calling Salesforce", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientSalesforceException("interrupted", e);
        }
    }
}
