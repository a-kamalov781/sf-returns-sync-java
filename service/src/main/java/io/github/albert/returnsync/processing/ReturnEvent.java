package io.github.albert.returnsync.processing;

import org.apache.avro.generic.GenericRecord;

import java.math.BigDecimal;

public record ReturnEvent(String externalId, String orderNumber, BigDecimal amount, String eventId) {

    /** Avro strings arrive as org.apache.avro.util.Utf8, Salesforce numbers as double. */
    public static ReturnEvent from(GenericRecord r, String eventId) {
        Object amount = r.get("Amount__c");
        return new ReturnEvent(
                str(r.get("External_Id__c")),
                str(r.get("Order_Number__c")),
                amount == null ? null : BigDecimal.valueOf(((Number) amount).doubleValue()),
                eventId);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
