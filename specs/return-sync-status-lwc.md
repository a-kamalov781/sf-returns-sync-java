# Specification: Return Sync status on the record page

**Status**: Accepted

## Goal

A user who creates a `Return_Request__c` record cannot see the Java service's decision without reloading the
page. The service writes `Status__c` (`New` → `Approved`, `Rejected` or `Manual_Review`) about 1–3 s after the
insert, through the REST API, as the integration user, and the open record page does not notice. This spec
produces a Lightning Web Component on the record page that shows "waiting for a decision" while the status is
`New` and the decision as soon as it lands, without a reload, together with who made it and how long it took.

- **The component asks again on a short timer until the status leaves `New`, then stops.** Salesforce does not
  push a REST update by another user to an open record page, and the alternatives that would push it (Change
  Data Capture, a second platform event) would deliver every change to the object to every open page, each
  delivery counting against the Developer Edition's 10,000 a day event allowance.
- **"How long it took" is the time from the record's creation to its last modification.** The org has no
  decision timestamp; adding one would change the Java service and its permission set, which is out of
  proportion for a display.

## Deliverables

- A component on the `Return_Request__c` record page with four states:
  - **Waiting**: status is `New`; says the decision is pending and shows the time since creation.
  - **Decided**: status is `Approved`, `Rejected` or `Manual_Review`; shows the decision, the user who last
    modified the record, and the time from creation to last modification.
  - **No decision yet**: the status is still `New` more than 60 s after creation (the waiting limit); says the
    sync service may be down and offers a button that checks once more and, if still `New`, waits 60 s more
    from the click.
  - **Error**: the record cannot be read, or a refresh fails while waiting; shows the platform's error message
    and the same check-again button. If the read then succeeds and the status is decided, the component shows
    Decided; if it is still `New`, it waits 60 s from the click.
- A Lightning record page for `Return_Request__c` with the component, the record's fields and the header and
  actions of the current page, with `Status__c` read-only as on the current layout. It is the org default on
  desktop and phone.
- AGENTS.md's Verification table and CI line list the component test command and its expected result.

## Scope boundaries

- No change to the Java service, the Platform Event, the trigger, or the permission sets' object and field
  access.
- No decision timestamp or "decided by" field.
- No history of status changes; the component shows the current status only.
- The component is not offered for other objects or on list views, utility bars or the home page.
- Salesforce Classic is not supported.

## Constraints

- Works for a user with the `Return_Request_Manager` permission set and no other access to the object.
- Stops all background requests once the status leaves `New`, when the limit is reached, on an error, and when
  the user leaves the page.
- Background refreshes do not count against the org's daily API request allocation.
- Labels the time as "about N s" when it is under a minute.

## Completion criteria

- [ ] In the live Developer Edition, a record that is still `New` when its page opens shows the decision on the
      same page view, without a reload, within 5 s of its `LastModifiedDate`.
- [ ] A record opened after its decision shows the decided state immediately and makes no background requests.
- [ ] A record still `New` more than 60 s after creation opens in No decision yet.
- [ ] With the Java service stopped, a new record shows "No decision yet" once the limit passes, and the button
      shows the decision after the service is started again.
- [ ] The component's unit tests run without an org, pass locally and in the CI workflow, and cover all four
      states, the check-again button and the stop conditions.
- [ ] On the user's own phone, in the Salesforce mobile app after the deploy, the record page shows the
      component, the record's fields, and the header and actions.

## Decisions & rationale

- **Decision**: refresh on a timer while the status is `New`, and stop as soon as it is not.
  **Alternatives**: subscribe to Change Data Capture on `Return_Request__c` through the platform's streaming
  API in the component; have the Java service publish a "decided" platform event the component subscribes to.
  **Why**: the wait is about 1–3 s. A Change Data Capture subscription delivers every change to the object to
  every open page, and each delivery counts against the Developer Edition's 10,000 a day event allowance,
  shared with the Java service. The publish option is out under Scope boundaries (no change to the service or
  trigger). Salesforce's LWC guidance advises against refreshing in a timer loop because it loads the server;
  that cost is bounded here by the 60 s limit and the stop conditions, and those refreshes do not count
  against the daily API allowance.
  **Origin**: speccy, user-agreed.
- **Decision**: measure "how long it took" as last-modified time minus creation time (it came with the request),
  and "who" as the last-modified user (speccy, user-agreed).
  **Alternatives**: add a decision timestamp and decider field written by the Java service; read field
  history.
  **Why**: last-modified needs no extra read and no extra metadata. The status is read-only on the layout, so
  the last modification is the decision in normal use; the edit-after-decision case is under Assumptions. Field
  history is exact but needs a second data source beside the record read, and the service-written fields need a
  change to the service.
  **Origin**: User for the time measure; speccy, user-agreed for "who".
- **Decision**: a waiting limit of 60 s with a manual "check again".
  **Alternatives**: keep waiting until the user leaves; no limit with a slower interval.
  **Why**: the service normally answers in about 1–3 s, so a minute separates "slow" from "down", and an
  open tab must not keep calling forever.
  **Origin**: speccy, user-agreed.
- **Decision**: the first waiting limit and the elapsed time count from the record's creation. A wait started
  by the button lasts 60 s from the click.
  **Alternatives**: count the first limit from the moment the page opens; count the button's wait from
  creation too.
  **Why**: a record created while the service is down and opened later would otherwise show "waiting" with a
  timer at 0 and poll for another minute on every view. A button wait counted from creation would already be
  over when clicked and drop straight back to No decision yet.
  **Origin**: speccy, user-agreed.
- **Decision**: the new record page is the org default on desktop and phone.
  **Alternatives**: desktop only; an app or profile assignment.
  **Why**: an org default reaches every app and profile through two overrides in the object's metadata. An app
  or profile assignment covers only the apps or profiles it names, and desktop only leaves phone users without
  the status.
  **Origin**: speccy, proposed.
- **Decision**: a refresh that fails while waiting shows Error with a check-again button and stops the timer.
  **Alternatives**: keep polling through the failure; Error with no way back.
  **Why**: polling through a failure leaves the user with no explanation of why nothing changes. Error with no
  way back ends the wait for that page view and forces a reload, which is what the component exists to remove,
  and a single failed request is most likely on a phone.
  **Origin**: speccy, user-agreed.

## Assumptions

- A record's amount or order number is rarely edited after the decision. When it is, the decided state shows
  that later edit's user and time.
- Users open records in Lightning Experience or the Salesforce mobile app.

## Open questions

None.
