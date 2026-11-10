package io.github.albert.returnsync.auth;

/** accessToken + instanceUrl + orgId (orgId = tenantid for the Pub/Sub API). */
public record SalesforceToken(String accessToken, String instanceUrl, String orgId) {

    /** Never print the access token itself. */
    @Override
    public String toString() {
        return "SalesforceToken[instanceUrl=" + instanceUrl + ", orgId=" + orgId + "]";
    }
}
