package io.github.albert.returnsync.auth;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.albert.returnsync.config.SalesforceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ~30-line JWT Bearer client against a fake token endpoint, with a throwaway RSA key. */
class JwtBearerTokenServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @TempDir Path dir;

    final JsonMapper json = JsonMapper.builder().build();
    KeyPair keys;
    JwtBearerTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        Path pem = dir.resolve("sf_jwt_key.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(UTF_8)).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        SalesforceProperties props = new SalesforceProperties(wm.baseUrl(), "https://login.salesforce.com",
                "3MVG9-consumer-key", "integration@example.com", pem, "68.0",
                "localhost", 7443, "/event/Return_Requested__e", 25);
        service = new JwtBearerTokenService(props, json);
    }

    private void stubTokenOk(String accessToken) {
        wm.stubFor(post(urlEqualTo("/services/oauth2/token")).willReturn(okJson("""
                {"access_token":"%s","scope":"api","instance_url":"https://acme.my.salesforce.com",
                 "id":"https://login.salesforce.com/id/00D5g000000AbCdEAK/0055g000000XyZaAAK",
                 "token_type":"Bearer"}""".formatted(accessToken))));
    }

    @Test
    void signsRs256AssertionAndParsesOrgId() throws Exception {
        stubTokenOk("tok-1");

        SalesforceToken token = service.get();

        assertThat(token.accessToken()).isEqualTo("tok-1");
        assertThat(token.instanceUrl()).isEqualTo("https://acme.my.salesforce.com");
        assertThat(token.orgId()).isEqualTo("00D5g000000AbCdEAK");

        LoggedRequest req = wm.findAll(postRequestedFor(urlEqualTo("/services/oauth2/token"))).getFirst();
        assertThat(req.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        String body = req.getBodyAsString();
        assertThat(body).startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=");

        String jwt = URLDecoder.decode(body.substring(body.indexOf("assertion=") + "assertion=".length()), UTF_8);
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), UTF_8)).isEqualTo("{\"alg\":\"RS256\"}");

        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("iss").asString()).isEqualTo("3MVG9-consumer-key");
        assertThat(claims.get("sub").asString()).isEqualTo("integration@example.com");
        assertThat(claims.get("aud").asString()).isEqualTo("https://login.salesforce.com");
        long exp = claims.get("exp").asLong();
        assertThat(exp).isBetween(Instant.now().getEpochSecond(), Instant.now().plusSeconds(181).getEpochSecond());

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void cachesTokenUntilInvalidated() {
        stubTokenOk("tok-1");
        SalesforceToken first = service.get();
        assertThat(service.get()).isSameAs(first);
        wm.verify(1, postRequestedFor(urlEqualTo("/services/oauth2/token")));

        service.invalidate(new SalesforceToken("other", "x", "y"));   // not the cached one: ignored
        assertThat(service.peek()).isSameAs(first);

        stubTokenOk("tok-2");
        service.invalidate(first);
        assertThat(service.get().accessToken()).isEqualTo("tok-2");
        wm.verify(2, postRequestedFor(urlEqualTo("/services/oauth2/token")));
    }

    @Test
    void rejectedJwtFailsWithSalesforceError() {
        wm.stubFor(post(urlEqualTo("/services/oauth2/token")).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"user hasn't approved this consumer\"}")));

        assertThatThrownBy(service::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("invalid_grant");
    }
}
