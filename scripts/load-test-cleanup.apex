// Deletes the records created by scripts/load-test.apex (Order_Number__c starts with LOAD-) and empties
// them from the Recycle Bin. Developer Edition has 5 MB of data storage and most records count as 2 KB,
// so ~2,500 records fill it. Run AFTER the service has processed everything (or the PATCH gets 404 ->
// dead letter, which is harmless but noisy):
//   sf apex run --file scripts/load-test-cleanup.apex --target-org returns-dev
// One run removes up to 4,500 records (delete + emptyRecycleBin = 9,000 of the 10,000 DML rows per
// transaction); run it again until it prints left=0.
List<Return_Request__c> rows = [
    SELECT Id FROM Return_Request__c WHERE Order_Number__c LIKE 'LOAD-%' LIMIT 4500
];
if (!rows.isEmpty()) {
    delete rows;
    Database.emptyRecycleBin(rows);
}
Integer left = [SELECT COUNT() FROM Return_Request__c WHERE Order_Number__c LIKE 'LOAD-%'];
System.debug(LoggingLevel.INFO, 'LOAD_TEST_CLEANUP deleted=' + rows.size() + ' left=' + left);
