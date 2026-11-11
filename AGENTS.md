# AGENTS.md

Instructions for coding agents working in this repository. Humans: see README.md and docs/.

## Layout

- `service/` — Java 25 / Spring Boot 4.1 service (Maven wrapper). Subscribes to `Return_Requested__e` over the
  Pub/Sub API and writes `Status__c` back with a batched REST upsert.
- `salesforce/` — SFDX project: `Return_Request__c`, `Return_Requested__e`, trigger + handler + Apex tests,
  permission sets, layout, tab, the `returnSyncStatus` LWC (Jest tests in `__tests__`) and the `Return_Request__c`
  record page that carries it (org default on desktop and phone through the object's View overrides).
- `scripts/` — load-test and memory-sampling scripts. `docs/` — org setup and load-test guides.

## Toolchain

JDK 25, Node and the Salesforce CLI (`sf`) on `PATH`. The LWC Jest tests need the Node range in
`salesforce/package.json` under `engines`. That is the range `npm ci` accepts without engine warnings, set by
`vitest`, a peer dependency that `sfdx-lwc-jest` installs (checked on Node 26 on Windows; CI runs Node 22). Docker is optional locally: without it the Testcontainers tests are skipped (CI
always runs them). The live org is the Developer Edition behind the CLI alias `returns-dev` (admin login).

## Verification

Run these and keep them green. Each one was checked on a clean tree.

| What | Command (from the repo root) | Expect |
|---|---|---|
| Java build, unit + integration tests | `cd service && ./mvnw -B verify` | `BUILD SUCCESS`; 44 unit tests; the 5 `*IT` tests skip without Docker |
| Salesforce metadata compiles, Apex tests pass (changes nothing in the org) | `cd salesforce && sf project deploy validate --source-dir force-app --target-org returns-dev --test-level RunLocalTests --wait 20` | `Status: Succeeded`, all Apex tests passing; for `returnSyncStatus`, the deploy lists only its `.html`, `.js` and `.js-meta.xml` (no `__tests__`) |
| LWC unit tests (no org needed) | `cd salesforce && npm ci && npm run test:unit` | 62 tests pass |
| Shell scripts parse | `for f in scripts/*.sh; do bash -n "$f"; done` | no output |

CI (`.github/workflows/ci.yml`) runs the same checks on every push to `main` and on pull requests: `service`
(verify with Docker), `docker-smoke` (image + compose), `lwc` (Jest unit tests, no org). `apex` (deploy validate
against the live org) needs org secrets and runs on push only.

## Deploying to the live org

`sf project deploy start --source-dir <dirs> --target-org returns-dev`. Metadata without Apex (layouts, tabs,
permission sets, LWC) deploys without running tests. Anything with Apex runs `RunLocalTests` (production-type
org). Never deploy to change data; never delete org records except the load-test ones (`Order_Number__c` starting
with `LOAD-`).

## Secrets

`.env`, `secrets/`, `*.pem`, `*.key` are git-ignored and must stay so. Never print, commit, or paste a key, token,
or the Consumer Key. The JWT private key is `secrets/sf_jwt_key.pem`.

## Worktree init

Parallel tasks run in git worktrees, which lack git-ignored state. Recreate it from the main checkout.

**Gather**

- `MAIN` — `git rev-parse --show-toplevel`

**Apply**

- `ln -s ${MAIN}/secrets secrets`
- `ln -s ${MAIN}/.env .env`
- `test -d ${MAIN}/salesforce/node_modules && ln -s ${MAIN}/salesforce/node_modules salesforce/node_modules || true`

## Writing

Commit messages, README and docs are in English: lead with the point, give the numbers, say what was not
verified. Code comments explain why, not what.
