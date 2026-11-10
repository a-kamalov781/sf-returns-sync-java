package io.github.albert.returnsync.salesforce;

/** Worth retrying: 429, 5xx, I/O errors, REQUEST_LIMIT_EXCEEDED, 401 that survived a token refresh. */
public class TransientSalesforceException extends RuntimeException {
    public TransientSalesforceException(String message) { super(message); }
    public TransientSalesforceException(String message, Throwable cause) { super(message, cause); }
}
