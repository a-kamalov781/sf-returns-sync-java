# Decision log: Return Sync status on the record page

What the run is working from, and how each position was reached. The spec holds the current position; this file
holds the history.

## Seed

The user asked for a next improvement to `sf-returns-sync-java` built with speccy, suggesting a Flow or an LWC
component, delivered as a PR that carries the spec and this log. The request handed to speccy named the feature:
an LWC "Return Sync status" (`returnSyncStatus`) on the `Return_Request__c` record page that shows "waiting for a
decision" while `Status__c` is `New`, then the decision, who set it and how long it took (`CreatedDate` →
`LastModifiedDate`), without a reload; stops refreshing once decided or after a timeout; handles a record that
never gets a decision; Jest tests, FlexiPage placement, a live check in the Developer Edition, CI coverage.

Treated as settled: the component type (LWC over Flow), the object, the four behaviours above, the time
measure, and the deliverables. The interview asked nothing further.

## Entries

- **The second wait after "check again" counts from the click.** Proposed in the draft: the waiting limit counts
  from the record's creation (Decision 4). Round-2 critique: on a record already past the limit, the button's
  "wait again" would end at once, so the button could never wait. Decided: the first wait and the elapsed time
  count from creation; a second wait lasts 60 s from the click. Origin: speccy, user-agreed (the user took all
  round-2 findings).
