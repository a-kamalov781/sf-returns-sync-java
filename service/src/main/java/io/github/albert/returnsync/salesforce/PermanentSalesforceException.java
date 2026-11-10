package io.github.albert.returnsync.salesforce;

/** Retrying will not help (4xx other than 401/429, e.g. 404 unknown External ID): goes to dead letters. */
public class PermanentSalesforceException extends RuntimeException {
    public PermanentSalesforceException(String message) { super(message); }
}
