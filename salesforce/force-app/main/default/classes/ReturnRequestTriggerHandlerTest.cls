@IsTest
private class ReturnRequestTriggerHandlerTest {

    /**
     * Deployed custom fields get no field-level security on profiles, so the deploying admin can't write them
     * (DmlException "fields being inaccessible", found on the first live deploy). Run as a user with the same
     * permission set real users get: this also proves the permission set is enough.
     */
    private static User manager() {
        Profile p = [SELECT Id FROM Profile WHERE Name = 'Standard User' LIMIT 1];
        String username = 'rr-manager-' + System.currentTimeMillis() + '@example.com.test';
        User u = new User(
            Alias = 'rrmgr', Email = username, Username = username, LastName = 'Return Manager',
            ProfileId = p.Id, EmailEncodingKey = 'UTF-8', LanguageLocaleKey = 'en_US',
            LocaleSidKey = 'en_US', TimeZoneSidKey = 'America/Los_Angeles');
        insert u;
        PermissionSet ps = [SELECT Id FROM PermissionSet WHERE Name = 'Return_Request_Manager' LIMIT 1];
        insert new PermissionSetAssignment(AssigneeId = u.Id, PermissionSetId = ps.Id);
        return u;
    }

    @IsTest
    static void publishesOneEventPerRecordInBulk() {
        System.runAs(manager()) {
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
    }

    @IsTest
    static void keepsProvidedExternalIdAndStatus() {
        System.runAs(manager()) {
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
}
