package io.github.albert.returnsync.salesforce;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** URL, Bearer header, one retry on 401 with a fresh token, 5xx/429 vs 4xx classification. */
class SalesforceRestClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/services/data/v68.0/sobjects/Return_Request__c/External_Id__c/RR-1";

    JwtBearerTokenService tokens = mock(JwtBearerTokenService.class);
    SalesforceRestClient client;
    SalesforceToken t1, t2;

    @BeforeEach
    void setUp() {
        t1 = new SalesforceToken("token-1", wm.baseUrl(), "00D000000000001");
        t2 = new SalesforceToken("token-2", wm.baseUrl(), "00D000000000001");
        client = new SalesforceRestClient(tokens, JsonMapper.builder().build(), "68.0");
    }

    @Test
    void sendsPatchUpsertWithUpdateOnly() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(okJson("{\"id\":\"a01\",\"success\":true,\"created\":false}")));

        client.updateStatus("RR-1", "Approved");

        wm.verify(patchRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("updateOnly", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .withRequestBody(equalToJson("{\"Status__c\":\"Approved\"}")));
    }

    @Test
    void legacyNoContentIsSuccess() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(noContent()));

        client.updateStatus("RR-1", "Approved");

        wm.verify(1, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void on401InvalidatesTokenAndRetriesOnce() {
        when(tokens.get()).thenReturn(t1, t2);
        wm.stubFor(patch(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(unauthorized()));
        wm.stubFor(patch(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer token-2"))
                .willReturn(okJson("{\"success\":true}")));

        client.updateStatus("RR-1", "Approved");

        verify(tokens).invalidate(t1);
        wm.verify(2, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void repeated401IsTransientNotDeadLettered() {
        when(tokens.get()).thenReturn(t1, t2);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(unauthorized()));

        assertThrows(TransientSalesforceException.class, () -> client.updateStatus("RR-1", "Approved"));
        wm.verify(2, patchRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void serverErrorIsTransient() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(serviceUnavailable()));
        assertThrows(TransientSalesforceException.class, () -> client.updateStatus("RR-1", "Approved"));
    }

    @Test
    void requestLimitExceededIsTransient() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403)
                .withBody("[{\"errorCode\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"TotalRequests Limit exceeded.\"}]")));
        assertThrows(TransientSalesforceException.class, () -> client.updateStatus("RR-1", "Approved"));
    }

    @Test
    void notFoundIsPermanent() {
        when(tokens.get()).thenReturn(t1);
        wm.stubFor(patch(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)
                .withBody("[{\"errorCode\":\"NOT_FOUND\",\"message\":\"Provided external ID field does not exist or is not accessible\"}]")));
        assertThrows(PermanentSalesforceException.class, () -> client.updateStatus("RR-1", "Approved"));
    }

    @Test
    void connectionErrorIsTransient() {
        SalesforceToken dead = new SalesforceToken("token-1", "http://127.0.0.1:1", "00D000000000001");
        when(tokens.get()).thenReturn(dead);
        assertThrows(TransientSalesforceException.class, () -> client.updateStatus("RR-1", "Approved"));
    }

    @Test
    void pathSegmentEncodesSpaceAsPercent20() {
        assertThat(SalesforceRestClient.pathSegment("a b/c")).isEqualTo("a%20b%2Fc");
    }
}
