@IsTest
private class ReturnRequestTriggerHandlerTest {

    @IsTest
    static void publishesOneEventPerRecordInBulk() {
        List<Return_Request__c> requests = new List<Return_Request__c>();
        for (Integer i = 0; i < 200; i++) {
            requests.add(new Return_Request__c(Order_Number__c = 'ORD-' + i, Amount__c = 10 + i));
        }

        Test.startTest();
        insert requests;
        Test.stopTest();   // PublishAfterCommit events are delivered after stopTest

        Assert.areEqual(200, ReturnRequestTriggerHandler.publishedCount, 'one event per record');
        Assert.areEqual(200, [SELECT COUNT() FROM Return_Request__c
                              WHERE External_Id__c != null AND Status__c = 'New']);
    }

    @IsTest
    static void keepsProvidedExternalIdAndStatus() {
        Return_Request__c r = new Return_Request__c(External_Id__c = 'RR-GIVEN', Status__c = 'Manual_Review',
                                                    Order_Number__c = 'ORD-1', Amount__c = 1);

        Test.startTest();
        insert r;
        Test.stopTest();

        Return_Request__c saved = [SELECT External_Id__c, Status__c FROM Return_Request__c WHERE Id = :r.Id];
        Assert.areEqual('RR-GIVEN', saved.External_Id__c);
        Assert.areEqual('Manual_Review', saved.Status__c);
        Assert.areEqual(1, ReturnRequestTriggerHandler.publishedCount);
    }
}
