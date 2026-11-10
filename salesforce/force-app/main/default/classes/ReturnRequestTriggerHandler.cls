/**
 * Logic for ReturnRequestTrigger. Bulk-safe: no SOQL/DML in loops, one EventBus.publish per trigger batch
 * (up to 200 records), SaveResult checked by index instead of "fire and pray".
 * Insert only: the Java service updates Status__c, and an update trigger would publish again (a loop).
 */
public with sharing class ReturnRequestTriggerHandler {

    @TestVisible
    private static Integer publishedCount = 0;

    public static void assignDefaults(List<Return_Request__c> records) {
        for (Return_Request__c r : records) {
            if (String.isBlank(r.External_Id__c)) {
                r.External_Id__c = UUID.randomUUID().toString();
            }
            if (r.Status__c == null) {
                r.Status__c = 'New';
            }
        }
    }

    public static void publishReturnRequested(List<Return_Request__c> records) {
        List<Return_Requested__e> events = new List<Return_Requested__e>();
        for (Return_Request__c r : records) {
            events.add(new Return_Requested__e(
                External_Id__c = r.External_Id__c,
                Order_Number__c = r.Order_Number__c,
                Amount__c = r.Amount__c
            ));
        }
        List<Database.SaveResult> results = EventBus.publish(events);   // one call for the whole batch
        for (Integer i = 0; i < results.size(); i++) {
            if (results[i].isSuccess()) {
                publishedCount++;
                continue;
            }
            for (Database.Error err : results[i].getErrors()) {
                System.debug(LoggingLevel.ERROR, 'Publish failed for ' + records[i].External_Id__c
                    + ': ' + err.getStatusCode() + ' ' + err.getMessage());
            }
        }
    }
}
