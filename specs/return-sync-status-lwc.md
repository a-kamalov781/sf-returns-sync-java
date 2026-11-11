# Specification: Return Sync status on the record page

**Status**: Draft

## Goal

A user who creates a `Return_Request__c` record cannot see the Java service's decision without reloading the
page: the service writes `Status__c` (`New` → `Approved`, `Rejected` or `Manual_Review`) about 1–2 s after the
insert, through the REST API, as the integration user, and the open record page does not notice. This spec
produces a Lightning Web Component on the record page that shows "waiting for a decision" while the status is
`New` and the decision as soon as it lands, without a reload, together with who made it and how long it took.

- **The component asks again on a short timer until the status leaves `New`, then stops.** Salesforce does not
  push a REST update by another user to an open record page, and the alternatives that would push it (Change
  Data Capture, a second platform event) add org setup and spend the Developer Edition's event-delivery
  allowance for a wait that lasts about two seconds.
- **"How long it took" is the time from the record's creation to its last modification.** The org has no
  decision timestamp; adding one would change the Java service, its permission set and the Collections upsert,
  which is out of proportion for a display.

## Deliverables

- A component on the `Return_Request__c` record page with four states:
  - **Waiting**: status is `New`; says the decision is pending and shows elapsed time.
  - **Decided**: status is `Approved`, `Rejected` or `Manual_Review`; shows the decision, the user who last
    modified the record, and the time from creation to last modification.
  - **No decision yet**: the status is still `New` after the waiting limit; says the sync service may be down
    and offers a button that checks once more and, if still `New`, waits again for one more limit.
  - **Error**: the record cannot be read; shows the platform's error message.
- The record page shows the component without a reload as soon as the decision is written, on any device size
  the page supports.
- Unit tests for the component that run without an org.
- A CI job that runs those tests on every push and pull request.

## Scope boundaries

- No change to the Java service, the Platform Event, the trigger, or the permission sets' object and field
  access.
- No decision timestamp or "decided by" field. The decided state uses the last-modified user and time.
- No history of status changes; the component shows the current status only.
- The component is not offered for other objects or on list views, utility bars or the home page.

## Constraints

- Works for a user with the `Return_Request_Manager` permission set and no other access to the object.
- Stops all background requests once the status leaves `New`, when the limit is reached, and when the user
  leaves the page.
- Places no extra load on the API allowance: refreshes go through the page's own record data service, which
  is not counted against the org's daily API requests.
- Labels the time as "about N s" when it is under a minute: `CreatedDate` and `LastModifiedDate` have one
  second resolution, so a sub-second answer reads as 0–1 s.

## Completion criteria

- [ ] In the live Developer Edition, a record created from the UI shows "waiting" and then the decision on the
      same page view, without a reload, within 5 s of saving.
- [ ] A record that is opened after its decision shows the decided state immediately and makes no background
      requests.
- [ ] With the Java service stopped, a new record shows "No decision yet" once the limit passes, and the button
      shows the decision after the service is started again.
- [ ] The component's unit tests pass locally and in CI, covering all four states and the stop conditions.
- [ ] The existing verification commands in `AGENTS.md` still pass.

## Decisions & rationale

- **Decision**: refresh on a timer while the status is `New`, and stop as soon as it is not.
  **Alternatives**: subscribe to Change Data Capture on `Return_Request__c` through the platform's streaming
  API in the component; have the Java service publish a "decided" platform event the component subscribes to.
  **Why**: the wait is about 2 s, and both push options need extra org setup and use the Developer Edition's
  event-delivery allowance (10,000 a day, shared with the Java service), which the load tests already spend.
  **Origin**: speccy, proposed.
- **Decision**: measure "how long it took" as last-modified time minus creation time, and "who" as the
  last-modified user.
  **Alternatives**: add a decision timestamp and decider field written by the Java service; read field
  history.
  **Why**: both alternatives change the service or the object's configuration for a display, and the
  record's status is read-only on the layout, so the last modification is the decision in normal use.
  **Origin**: speccy, proposed.
- **Decision**: a waiting limit of 60 s with a manual "check again".
  **Alternatives**: keep waiting until the user leaves; no limit with a slower interval.
  **Why**: the service normally answers in 1–6 s, so a minute separates "slow" from "down", and an
  open tab must not keep calling forever.
  **Origin**: speccy, proposed.

## Assumptions

- A record's amount or order number is rarely edited after the decision. When it is, the decided state shows
  that later edit's user and time.
- Users open records in Lightning Experience; Salesforce Classic is not supported.
- The record page in use is the object's default Lightning record page, activated as the org default for all
  form factors.

## Open questions

None.
