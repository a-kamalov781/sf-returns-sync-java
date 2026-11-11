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
- **Error is no longer a dead end.** Proposed: a refresh that fails while waiting shows Error and stops the timer,
  with no way back short of a reload (Decision 6, speccy, proposed). Round-3 critique: one failed request, most
  likely on a phone, ends the wait for that page view. Decided: Error keeps stopping the timer and gains the same
  "check again" button as No decision yet. Origin: speccy, user-agreed.
- **The phone check stays, run by the user.** Round-3 critique: the build cannot open the Salesforce mobile app.
  Decided: the criterion stays and the user runs it on their own phone after the deploy, rather than cutting it
  as the second-user check was cut in round 1. Origin: User.
- **The time measure is the user's.** Decision 2 was tagged speccy, user-agreed; the creation-to-last-modified
  measure came with the request (see Seed). Retagged User; "who = last-modified user" stays speccy, user-agreed.
- **Plan approved at review; three plan decisions ratified.** The one-clock design with per-request generation
  numbers, the derived (not stored) state, and the page built from the highlights panel, the component and the
  layout-driven detail panel were proposed by speccy and hardened over three plan-critique rounds (round 1:
  recovery from Error and the blank pre-load state; round 2: stale requests overwriting the button's result;
  round 3: nothing restarted the clock after an error or a reconnect). The user approved them as planned.
  Origin: speccy, user-agreed.
- **The live checks start the local service from the newest event.** Its stored replay position predated a
  1,200-event load run, so a restart would have replayed that backlog against the daily event allowance. The
  user chose clearing the position over draining it. Origin: User.
- **"Stops when the user leaves the page" now reads "or at the latest at the limit".** The review found the
  component stops only when Lightning disconnects it; a page kept alive for back navigation, or a background
  console tab, keeps a waiting record refreshing until its 60 s limit (at most about 30 LDS requests, none
  counted against the API allocation). Alternatives: stop on a page change through `CurrentPageReference`.
  Decided: amend the spec's constraint to match the plan's accepted risk; no code change. Origin: User.
