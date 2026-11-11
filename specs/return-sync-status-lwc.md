# Specification: Return Sync status on the record page

**Status**: Draft

## Goal

A user who creates a `Return_Request__c` record cannot see the Java service's decision without reloading the
page. The service writes `Status__c` (`New` → `Approved`, `Rejected` or `Manual_Review`) about 1–3 s after the
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
  - **Waiting**: status is `New`; says the decision is pending and shows the time since creation.
  - **Decided**: status is `Approved`, `Rejected` or `Manual_Review`; shows the decision, the user who last
    modified the record, and the time from creation to last modification.
  - **No decision yet**: the status is still `New` more than 60 s after creation (the waiting limit), including
    when the record is opened that late; says the sync service may be down and offers a button that checks once
    more and, if still `New`, waits again for one more limit.
  - **Error**: the record cannot be read, or a refresh fails while waiting; shows the platform's error message
    and stops the timer.
- A Lightning record page for `Return_Request__c` with the component and the record's fields. It is the org
  default on desktop and phone.
- AGENTS.md's Verification table and CI line list the component test command and its expected result.

## Scope boundaries

- No change to the Java service, the Platform Event, the trigger, or the permission sets' object and field
  access.
- No decision timestamp or "decided by" field. The decided state uses the last-modified user and time.
- No history of status changes; the component shows the current status only.
- The component is not offered for other objects or on list views, utility bars or the home page.

## Constraints

- Works for a user with the `Return_Request_Manager` permission set and no other access to the object.
- Stops all background requests once the status leaves `New`, when the limit is reached, on an error, and when
  the user leaves the page.
- Background refreshes do not count against the org's daily API request allocation.
- Labels the time as "about N s" when it is under a minute. `CreatedDate` and `LastModifiedDate` have one second
  resolution, so a sub-second answer reads as 0–1 s.

## Completion criteria

- [ ] In the live Developer Edition, a record created from the UI shows "waiting" and then the decision on the
      same page view, without a reload, within 5 s of the record's `LastModifiedDate`.
- [ ] A record opened after its decision shows the decided state immediately and makes no background requests.
- [ ] A record still `New` more than 60 s after creation opens in No decision yet.
- [ ] With the Java service stopped, a new record shows "No decision yet" once the limit passes, and the button
      shows the decision after the service is started again.
- [ ] The component's unit tests run without an org, pass locally and in the CI workflow, and cover all four
      states and the stop conditions.
- [ ] On a phone (the Salesforce mobile app), the record page shows the component and the record's fields.

## Decisions & rationale

- **Decision**: refresh on a timer while the status is `New`, and stop as soon as it is not.
  **Alternatives**: subscribe to Change Data Capture on `Return_Request__c` through the platform's streaming
  API in the component; have the Java service publish a "decided" platform event the component subscribes to.
  **Why**: the wait is about 1–3 s. A Change Data Capture subscription delivers every change to the object to
  every open page, and each delivery counts against the Developer Edition's 10,000 a day event allowance,
  shared with the Java service. The publish option is out under Scope boundaries (no change to the service or
  trigger). Salesforce's LWC guidance advises against refreshing in a timer loop because it loads the server;
  that cost is bounded here by the 60 s limit and the stop conditions, at most about 30 refreshes per record
  view, and those refreshes do not count against the daily API allowance.
  **Origin**: speccy, user-agreed.
- **Decision**: measure "how long it took" as last-modified time minus creation time, and "who" as the
  last-modified user.
  **Alternatives**: add a decision timestamp and decider field written by the Java service; read field
  history.
  **Why**: last-modified needs no extra read and no extra metadata. The status is read-only on the layout, so
  the last modification is the decision in normal use; the edit-after-decision case is under Assumptions. Field
  history is exact but needs a second data source beside the record read, and the service-written fields need a
  change to the service.
  **Origin**: speccy, user-agreed.
- **Decision**: a waiting limit of 60 s with a manual "check again".
  **Alternatives**: keep waiting until the user leaves; no limit with a slower interval.
  **Why**: the service normally answers in about 1–3 s, so a minute separates "slow" from "down", and an
  open tab must not keep calling forever.
  **Origin**: speccy, user-agreed.
- **Decision**: the waiting limit and the elapsed time both count from the record's creation.
  **Alternatives**: count from the moment the page opens.
  **Why**: a record created while the service is down and opened later would otherwise show "waiting" with a
  timer at 0 and poll for another minute on every view.
  **Origin**: speccy, proposed.
- **Decision**: the new record page is the org default on desktop and phone.
  **Alternatives**: desktop only; an app or profile assignment.
  **Why**: the repo has no record page, and the status must show for every user on every device.
  **Origin**: speccy, proposed.
- **Decision**: a refresh that fails while waiting shows Error and stops the timer.
  **Alternatives**: keep waiting and polling through the failure.
  **Why**: a failing record read is unlikely to recover quickly, and the Error state is the one place the user
  learns why nothing changes.
  **Origin**: speccy, proposed.

## Assumptions

- A record's amount or order number is rarely edited after the decision. When it is, the decided state shows
  that later edit's user and time.
- Users open records in Lightning Experience; Salesforce Classic is not supported.

## Open questions

None.
