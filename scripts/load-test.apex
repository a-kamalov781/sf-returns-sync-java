// One load-test round: inserts N Return_Request__c records in chunks of 200.
// Each chunk fires the trigger once -> one EventBus.publish -> N platform events in total.
// Run through scripts/load-test.sh, which replaces the two values below. Running it by hand also works:
//   sf apex run --file scripts/load-test.apex --target-org returns-dev
//
// Limits per anonymous Apex transaction (well inside): 10,000 DML rows, 150 DML statements
// (each insert chunk = 1 + 1 EventBus.publish), 10 s CPU.
Integer n = 100;              // LOAD_TEST_N
String runTag = 'manual';     // LOAD_TEST_TAG

if (n < 1 || n > 1000) {
    throw new IllegalArgumentException('n must be 1..1000 per round, got ' + n);
}

List<Return_Request__c> chunk = new List<Return_Request__c>();
Integer inserted = 0;
for (Integer i = 0; i < n; i++) {
    // Amounts 0..699 give all three outcomes: <= 500 Approved, > 500 Manual_Review.
    chunk.add(new Return_Request__c(
        Order_Number__c = 'LOAD-' + runTag + '-' + i,
        Amount__c = Math.mod(i * 37, 700)
    ));
    if (chunk.size() == 200 || i == n - 1) {
        insert chunk;
        inserted += chunk.size();
        chunk = new List<Return_Request__c>();
    }
}
System.debug(LoggingLevel.INFO, 'LOAD_TEST inserted=' + inserted + ' tag=' + runTag);
