package io.github.albert.returnsync.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.albert.returnsync.config.SalesforceProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * OAuth 2.0 JWT Bearer flow (Salesforce Help: "OAuth 2.0 JWT Bearer Flow for Server-to-Server Integration"),
 * built on the JDK only: RS256 = SHA256withRSA over base64url(header).base64url(claims).
 * <p>
 * The token response carries no expiry (it depends on the org session timeout) and the flow never issues a
 * refresh token, so the strategy is: keep the token until Salesforce answers 401, then sign a new JWT.
 */
@Component
public class JwtBearerTokenService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("instance_url") String instanceUrl,
                         @JsonProperty("id") String id) {}

    private final SalesforceProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile SalesforceToken cached;
    private volatile PrivateKey privateKey;   // read lazily, so tests can start the context without a key file

    public JwtBearerTokenService(SalesforceProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    /** Cached token or a fresh one. Thread-safe. */
    public SalesforceToken get() {
        SalesforceToken t = cached;
        if (t != null) return t;
        synchronized (this) {
            if (cached == null) cached = requestToken();
            return cached;
        }
    }

    /** Current cached token without fetching one (may be null). */
    public SalesforceToken peek() {
        return cached;
    }

    /** Drop the token only if it is still the one that got a 401 (don't drop a fresh one another thread got). */
    public synchronized void invalidate(SalesforceToken stale) {
        if (stale != null && cached == stale) cached = null;
    }

    private SalesforceToken requestToken() {
        try {
            String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion=" + enc(buildAssertion());
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.loginUrl() + "/services/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("JWT bearer token request failed: "
                        + resp.statusCode() + " " + resp.body());
            }
            TokenResponse tr = json.readValue(resp.body(), TokenResponse.class);
            // id = https://login.salesforce.com/id/{orgId}/{userId}
            String orgId = URI.create(tr.id()).getPath().split("/")[2];
            return new SalesforceToken(tr.accessToken(), tr.instanceUrl(), orgId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot obtain Salesforce token", e);
        }
    }

    String buildAssertion() throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", props.clientId());
        claims.put("sub", props.username());
        claims.put("aud", props.audience());
        claims.put("exp", Instant.now().plusSeconds(180).getEpochSecond()); // docs: at most 3 minutes ahead
        String header = b64url("{\"alg\":\"RS256\"}".getBytes(UTF_8));
        String payload = b64url(json.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey());
        signer.update(signingInput.getBytes(UTF_8));
        return signingInput + "." + b64url(signer.sign());
    }

    private PrivateKey privateKey() throws Exception {
        if (privateKey == null) {
            String pem = Files.readString(props.privateKeyPath())
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        return privateKey;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, UTF_8);
    }
}
