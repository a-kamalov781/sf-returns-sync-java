package io.github.albert.returnsync.salesforce;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.StatusUpdate;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * sObject Collections upsert: URL, updateOnly, body, Bearer header, per-record results in order, one retry on
 * 401 with a fresh token, 5xx/429/REQUEST_LIMIT_EXCEEDED/UNABLE_TO_LOCK_ROW transient, other 4xx permanent.
 * Response bodies are shaped like the ones from the live org (API 68.0).
 */
class SalesforceRestClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/services/data/v68.0/composite/sobjects/Return_Request__c/External_Id__c";
    private static final String OK_1 = "[{\"id\":\"a00fj00002mbSQTAA2\",\"success\":true,\"errors\":[],\"created\":false}]";

    JwtBearerTokenService tokens = mock(JwtBearerTokenService.class);
    SalesforceRestClient client;
    SalesforceToken t1, t2;

    @BeforeEach
    void setUp() {
        t1 = new SalesforceToken("token-1", wm.baseUrl(), "00D000000000001");
        t2 = new SalesforceToken("token-2", wm.baseUrl(), "00D000000000001");
        client = new SalesforceRestClient(tokens, JsonMapper.builder().build(), "68.0");
    }

    private static List<StatusUpdate> one() {
        return List.of(new StatusUpdate("RR-1", "Approved"));
    }

    @Test
    void sendsOneCollectionsUpsertWithUpdateOnly() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"id":"a01","success":true,"errors":[],"created":false},
                 {"id":"a02","success":true,"errors":[],"created":false}]""")));

        List<UpdateResult> results = client.updateStatuses(List.of(
                new StatusUpdate("RR-1", "Approved"), new StatusUpdate("RR-2", "Manual_Review")));

        assertThat(results).extracting(UpdateResult::externalId, UpdateResult::success)
                .containsExactly(tuple("RR-1", true), tuple("RR-2", true));
        wm.verify(1, patchRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("updateOnly", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .withRequestBody(equalToJson("""
                        {"allOrNone": false, "records": [
                          {"attributes": {"type": "Return_Request__c"}, "External_Id__c": "RR-1", "Status__c": "Approved"},
                          {"attributes": {"type": "Return_Request__c"}, "External_Id__c": "RR-2", "Status__c": "Manual_Review"}
                        ]}""")));
    }

    @Test
    void perRecordNotFoundIsReturnedNotThrown() {
        when(tokens.get()).thenReturn(t1);
        // exactly what the live org answered for an unknown External ID with updateOnly=true
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"id":"a00fj00002mbSQTAA2","success":true,"errors":[],"created":false},
                 {"success":false,"errors":[{"statusCode":"NOT_FOUND","message":"The requested resource does not exist","fields":[]}],"created":false}]""")));

        List<UpdateResult> results = client.updateStatuses(List.of(
                new StatusUpdate("RR-1", "Approved"), new StatusUpdate("RR-unknown", "Approved")));

        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1)).isEqualTo(
                new UpdateResult("RR-unknown", false, "NOT_FOUND", "The requested resource does not exist"));
    }

    @Test
    void moreThan200RecordsAreSplitIntoCalls() {
        when(tokens.get()).thenReturn(t1);
        String ok200 = "[" + String.join(",", IntStream.range(0, 200).mapToObj(i -> "{\"success\":true,\"errors\":[]}").toList()) + "]";
        String ok50 = "[" + String.join(",", IntStream.range(0, 50).mapToObj(i -> "{\"success\":true,\"errors\":[]}").toList()) + "]";
        wm.stubFor(patch(urlPathEqualTo(PATH)).inScenario("chunks").whenScenarioStateIs("Started")
                .willReturn(okJson(ok200)).willSetStateTo("second"));
        wm.stubFor(patch(urlPathEqualTo(PATH)).inScenario("chunks").whenScenarioStateIs("second")
                .willReturn(okJson(ok50)));

        List<StatusUpdate> updates = IntStream.range(0, 250).mapToObj(i -> new StatusUpdate("RR-" + i, "Approved")).toList();
        List<UpdateResult> results = client.updateStatuses(updates);

        assertThat(results).hasSize(250).allMatch(UpdateResult::success);
        assertThat(results.get(249).externalId()).isEqualTo("RR-249");
        wm.verify(2, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void on401InvalidatesTokenAndRetriesOnce() {
        when(tokens.get()).thenReturn(t1, t2);
        wm.stubFor(patch(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(unauthorized()));
        wm.stubFor(patch(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer token-2"))
                .willReturn(okJson(OK_1)));

        assertThat(client.updateStatuses(one())).singleElement().matches(UpdateResult::success);

        verify(tokens).invalidate(t1);
        wm.verify(2, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void repeated401IsTransientNotDeadLettered() {
        when(tokens.get()).thenReturn(t1, t2);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(unauthorized()));

        assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(one()));
        wm.verify(2, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void serverErrorIsTransient() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(serviceUnavailable()));
        assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(one()));
    }

    @Test
    void requestLimitExceededIsTransient() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403)
                .withBody("[{\"errorCode\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"TotalRequests Limit exceeded.\"}]")));
        assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(one()));
    }

    @Test
    void lockedRowIsTransientForTheWholeCall() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson(
                "[{\"success\":false,\"errors\":[{\"statusCode\":\"UNABLE_TO_LOCK_ROW\",\"message\":\"unable to obtain exclusive access\"}]}]")));
        assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(one()));
    }

    @Test
    void wholeBatchRejectedForAMissingPermissionIsTransientNotDeadLettered() {
        when(tokens.get()).thenReturn(t1);
        // what the live org answered when the integration user could not edit External_Id__c
        String denied = "{\"success\":false,\"errors\":[{\"statusCode\":\"INVALID_FIELD_FOR_INSERT_UPDATE\","
                + "\"message\":\"Unable to create/update fields: External_Id__c.\",\"fields\":[\"External_Id__c\"]}]}";
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson("[" + denied + "," + denied + "]")));

        TransientSalesforceException e = assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(List.of(
                new StatusUpdate("RR-1", "Approved"), new StatusUpdate("RR-2", "Approved"))));
        assertThat(e.getMessage()).contains("INVALID_FIELD_FOR_INSERT_UPDATE").contains("permission");
    }

    @Test
    void permissionErrorOnOneRecordNextToSuccessIsRecordSpecific() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"success":true,"errors":[]},
                 {"success":false,"errors":[{"statusCode":"INSUFFICIENT_ACCESS_OR_READONLY","message":"no access"}]}]""")));

        List<UpdateResult> results = client.updateStatuses(List.of(
                new StatusUpdate("RR-1", "Approved"), new StatusUpdate("RR-2", "Approved")));
        assertThat(results.get(1).errorCode()).isEqualTo("INSUFFICIENT_ACCESS_OR_READONLY");
    }

    @Test
    void badRequestIsPermanent() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withBody("[{\"errorCode\":\"JSON_PARSER_ERROR\",\"message\":\"Unexpected character\"}]")));
        assertThrows(PermanentSalesforceException.class, () -> client.updateStatuses(one()));
    }

    @Test
    void connectionErrorIsTransient() {
        SalesforceToken dead = new SalesforceToken("token-1", "http://127.0.0.1:1", "00D000000000001");
        when(tokens.get()).thenReturn(dead);
        assertThrows(TransientSalesforceException.class, () -> client.updateStatuses(one()));
    }
}
