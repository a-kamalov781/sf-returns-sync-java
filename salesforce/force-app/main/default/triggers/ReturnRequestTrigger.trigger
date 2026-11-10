trigger ReturnRequestTrigger on Return_Request__c (before insert, after insert) {
    if (Trigger.isBefore) {
        ReturnRequestTriggerHandler.assignDefaults(Trigger.new);
    } else {
        ReturnRequestTriggerHandler.publishReturnRequested(Trigger.new);
    }
}
