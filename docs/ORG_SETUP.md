# Org setup: from zero to a live run

This guide closes the three items the README lists as *not verified*: a run against a live org, Apex
compilation, and memory under load. Everything below is done by you, in your own free Developer Edition org.
Nothing here needs a paid licence.

> UI labels in Setup change between releases. If a label below doesn't match, search for it in Setup's
> Quick Find. The steps were written against the documentation (Winter '27, API 68.0) and **have not been
> run against an org yet**: please report what differs.

**Never commit:** `secrets/`, `.env`, `*.key`, `*.pem`, `*.crt` (all git-ignored). The private key stays on your
machine and, optionally, in one GitHub Actions secret.

Contents:

1. [Create the org and install the CLI](#1-create-the-org-and-install-the-cli)
2. [Deploy the metadata and run the Apex tests](#2-deploy-the-metadata-and-run-the-apex-tests)
3. [Key and certificate for JWT](#3-key-and-certificate-for-jwt)
4. [Integration user](#4-integration-user)
5. [External Client App with JWT Bearer](#5-external-client-app-with-jwt-bearer)
6. [Check the JWT login before starting the service](#6-check-the-jwt-login-before-starting-the-service)
7. [Configure and start the service](#7-configure-and-start-the-service)
8. [First-run checklist](#8-first-run-checklist)
9. [GitHub secrets for the `apex` CI job](#9-github-secrets-for-the-apex-ci-job)
10. [Load test and memory measurement](#10-load-test-and-memory-measurement)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Create the org and install the CLI

1. Sign up for a free Developer Edition at <https://developer.salesforce.com/signup>. Write down the admin
   username (it looks like an email but is unique across Salesforce).
2. Install the Salesforce CLI: `npm install --global @salesforce/cli` (Node 20+), then `sf --version`.
3. Log in as the admin, in the browser (this uses your password in Salesforce's own login page, not in this repo):

```bash
sf org login web --alias returns-dev --instance-url https://login.salesforce.com --set-default
```

4. Note the org's My Domain URL: `sf org display --target-org returns-dev` → `Instance Url`.

## 2. Deploy the metadata and run the Apex tests

From the repository root:

```bash
cd salesforce
sf project deploy start --source-dir force-app --target-org returns-dev --test-level RunLocalTests --wait 15
```

A Developer Edition is a production-type org, so Apex tests run on deploy. This is the first time the Apex
compiles. If it fails, the error names the file and line: fix, commit, deploy again.

Run the tests on their own (also shows coverage):

```bash
sf apex run test --target-org returns-dev --class-names ReturnRequestTriggerHandlerTest --code-coverage --result-format human --wait 10
```

Expected: 2 tests pass, `ReturnRequestTriggerHandler` coverage above 75%.

Check by hand that the trigger works (no service needed yet):

```bash
sf data create record --target-org returns-dev --sobject Return_Request__c --values "Order_Number__c=ORD-1 Amount__c=100"
sf data query --target-org returns-dev --query "SELECT Name, External_Id__c, Status__c FROM Return_Request__c ORDER BY CreatedDate DESC LIMIT 5"
```

Expected: `Status__c = New` and a UUID in `External_Id__c`.

## 3. Key and certificate for JWT

The service signs a JWT with a private key; Salesforce checks it with the certificate you upload. From the
repository root (OpenSSL 3 writes the key as PKCS#8, `BEGIN PRIVATE KEY`, which the service expects):

```bash
mkdir -p secrets
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=sf-returns-sync" -keyout secrets/sf_jwt_key.pem -out secrets/server.crt
```

```bash
head -1 secrets/sf_jwt_key.pem
```

It must print `-----BEGIN PRIVATE KEY-----`. If it prints `BEGIN RSA PRIVATE KEY` (old OpenSSL), convert it:
`openssl pkcs8 -topk8 -nocrypt -in secrets/sf_jwt_key.pem -out secrets/sf_jwt_key.pk8 && mv secrets/sf_jwt_key.pk8 secrets/sf_jwt_key.pem`.

Only `server.crt` (public) goes to Salesforce. `sf_jwt_key.pem` never leaves your machine, except into the
`SF_JWT_KEY` GitHub secret if you choose to (step 9).

## 4. Integration user

The service should not log in as the admin. Two options:

**A. Integration user (recommended, least privilege).** A Developer Edition includes free *Salesforce
Integration* user licences.

1. Setup → Users → New User. User License: **Salesforce Integration**. Profile: **Minimum Access - API Only
   Integrations**. Use a unique username, e.g. `returns-integration@<something-unique>.dev`. The user needs no
   password: JWT Bearer doesn't use one.
2. On the user: Permission Set License Assignments → add **Salesforce API Integration**.
3. Assign the project permission set:

```bash
sf org assign permset --target-org returns-dev --name Return_Sync_Integration --on-behalf-of returns-integration@<something-unique>.dev
```

If the assignment is refused because of the `ApiEnabled` user permission (API-only users already have API
access), remove the `<userPermissions>` block from
`salesforce/force-app/main/default/permissionsets/Return_Sync_Integration.permissionset-meta.xml`, deploy
again, assign again, and note it in an issue so the repo gets fixed.

**B. Quick start with the admin user.** Skip this step and use the admin username as `SF_USERNAME`. It works,
but a reviewer will ask why the integration runs as an admin; switch to A before showing the project.

## 5. External Client App with JWT Bearer

1. Setup → **External Client App Manager** → **New External Client App**.
   - Name: `Returns Sync`; contact email: yours; Distribution State: **Local**.
   - **Enable OAuth**. Callback URL: `http://localhost:1717/OauthRedirect` (required by the form, not used by JWT).
   - OAuth scopes: **Manage user data via APIs (api)** and **Perform requests at any time (refresh_token,
     offline_access)**.
   - Flow Enablement: **Enable JWT Bearer Flow** → upload `secrets/server.crt`.
   - Security: leave *Require secret for Web Server Flow* as is; JWT needs no secret.
2. Save, then open the app → **Policies** → Edit:
   - OAuth Policies → Plugin Policies → Permitted Users: **Admin approved users are pre-authorized**.
   - Select permission sets: **Return Sync Integration** (option A). For option B or for the CI job (step 9)
     also select the **System Administrator** profile.
   - Save.
3. Open **Settings** → OAuth Settings → **Consumer Key and Secret**. Copy the *Consumer Key* — that is
   `SF_CLIENT_ID`. You don't need the secret.

A new app can take a few minutes before the token endpoint accepts it. A Connected App with the same settings
(Use digital signatures + pre-authorized policy) also works, if your org still allows creating Connected Apps.

## 6. Check the JWT login before starting the service

The CLI uses the same flow, so this proves the app, certificate, user and pre-authorization are right:

```bash
sf org login jwt --client-id <CONSUMER_KEY> --jwt-key-file secrets/sf_jwt_key.pem --username <SF_USERNAME> --instance-url https://login.salesforce.com --alias returns-int
```

```bash
sf org display --target-org returns-int
```

Expected: `Connected Status: Connected`, `Username` = the integration user. Errors are listed in
[Troubleshooting](#11-troubleshooting).

## 7. Configure and start the service

```bash
cp .env.example .env
```

Edit `.env` (it is git-ignored):

| Variable | Value |
|---|---|
| `SF_LOGIN_URL` | `https://login.salesforce.com` (or your My Domain URL) |
| `SF_AUDIENCE` | `https://login.salesforce.com` — JWT `aud`, keep this even with My Domain |
| `SF_CLIENT_ID` | Consumer Key from step 5 |
| `SF_USERNAME` | integration user (step 4) |
| `DB_PASSWORD` | any local password |

The key is not a variable: compose mounts `./secrets/sf_jwt_key.pem` read-only at `/run/secrets/sf_jwt_key.pem`.

```bash
docker compose up --build -d
```

```bash
docker compose logs -f service
```

Expected in the log, in this order: Flyway `Successfully applied 1 migration`, then
`Subscribed to /event/Return_Requested__e with preset LATEST`. No `Reconnecting in` loop.

## 8. First-run checklist

Tick each line and keep the output: it becomes the evidence in the README.

**8.1 Token and subscription**

- [ ] Log shows `Subscribed to /event/Return_Requested__e with preset LATEST` and no errors for 5 minutes
      (keepalives arrive at least every 270 s; nothing is logged for them).

**8.2 One event end to end**

```bash
sf data create record --target-org returns-dev --sobject Return_Request__c --values "Order_Number__c=ORD-100 Amount__c=120"
sf data create record --target-org returns-dev --sobject Return_Request__c --values "Order_Number__c=ORD-101 Amount__c=900"
```

```bash
sf data query --target-org returns-dev --query "SELECT Name, Order_Number__c, Amount__c, Status__c FROM Return_Request__c ORDER BY CreatedDate DESC LIMIT 5"
```

- [ ] Within a few seconds `ORD-100` → `Approved`, `ORD-101` → `Manual_Review`.
- [ ] The database has both rows and a replay ID:

```bash
docker compose exec db psql -U returns -d returns -c "SELECT external_id, status, processed_at FROM processed_returns ORDER BY processed_at DESC LIMIT 5" -c "SELECT topic, length(replay_id) AS replay_bytes, updated_at FROM replay_state"
```

- [ ] Note the end-to-end latency: `processed_at` minus the record's `CreatedDate` (README → *Measured*).

**8.3 Restart resumes from the stored replay ID, without duplicates**

```bash
docker compose stop service
sf data create record --target-org returns-dev --sobject Return_Request__c --values "Order_Number__c=ORD-102 Amount__c=50"
sf data create record --target-org returns-dev --sobject Return_Request__c --values "Order_Number__c=ORD-103 Amount__c=60"
docker compose start service
docker compose logs --since 2m service | grep -E "Subscribed|Duplicate"
```

- [ ] Log shows `with preset CUSTOM` (resumed from the stored replay ID, not LATEST).
- [ ] `ORD-102` and `ORD-103` get `Approved` although the service was down when they were created.
- [ ] No duplicates:

```bash
docker compose exec db psql -U returns -d returns -c "SELECT count(*) AS rows, count(DISTINCT external_id) AS distinct_ids FROM processed_returns"
```

**8.4 Corrupted replay ID → EARLIEST, idempotency drops the duplicates**

```bash
docker compose stop service
docker compose exec db psql -U returns -d returns -c "UPDATE replay_state SET replay_id = '\x00'"
docker compose start service
docker compose logs --since 2m service | grep -E "error-code|preset|Duplicate"
```

- [ ] An `error-code=...replayid...` warning, then `with preset EARLIEST`, then `Duplicate ..., skipping` for
      events already processed (EARLIEST replays up to 72 h).
- [ ] The duplicate-count query above still shows `rows = distinct_ids`, and no `Status__c` changed back.

If the server answers the corrupted ID with an error code that doesn't contain `replayid`, the service falls back
to CUSTOM again and loops: copy the exact `error-code` from the log into an issue.

**8.5 Permanent error → dead letter**

```bash
docker compose exec db psql -U returns -d returns -c "SELECT external_id, reason, created_at FROM dead_letters ORDER BY id DESC LIMIT 5"
```

- [ ] Empty in a normal run. To force one: create a record, delete it in Salesforce before the service
      processes it (stop the service first), start the service → a `404` / `NOT_FOUND` row appears, the stream goes on.

**8.6 Graceful shutdown**

```bash
docker compose stop -t 30 service
```

- [ ] Stops in seconds, log ends with Hikari `Shutdown completed`, no stack traces.

## 9. GitHub secrets for the `apex` CI job

The `apex` job runs `sf project deploy validate --test-level RunLocalTests` on every push to `main`. It needs a user
who may deploy Apex — **the admin**, not the integration user. Add the System Administrator profile to the
External Client App policies (step 5.2) so the admin is pre-authorized too.

Set the three secrets yourself; `gh` asks for the value or reads it from a file, so nothing lands in your shell history:

```bash
gh secret set SF_CLIENT_ID --repo a-kamalov781/sf-returns-sync-java
gh secret set SF_USERNAME --repo a-kamalov781/sf-returns-sync-java
gh secret set SF_JWT_KEY --repo a-kamalov781/sf-returns-sync-java < secrets/sf_jwt_key.pem
```

`SF_USERNAME` here is the admin username. Then push a commit to `main` or re-run the latest run
(`gh run rerun <run-id> --repo a-kamalov781/sf-returns-sync-java`). Expected: job `apex` → step *Validate deploy
with local Apex tests* is green. Validation doesn't change the org.

A separate certificate for CI is better practice (revoke one without touching the other); with one Developer
Edition the same key is acceptable.

## 10. Load test and memory measurement

See [LOAD_TEST.md](LOAD_TEST.md): a scripted stream of events sized for Developer Edition limits, and a
one-hour `docker stats` sampler that prints the numbers to put into the README.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `invalid_grant: user hasn't approved this consumer` | user not pre-authorized | step 5.2: *Admin approved users are pre-authorized* + the permission set / profile of this user |
| `invalid_grant: audience is invalid` | `aud` not `https://login.salesforce.com` | keep `SF_AUDIENCE=https://login.salesforce.com` (sandboxes: `https://test.salesforce.com`) |
| `invalid_client_id` / `invalid_app_access` | wrong Consumer Key, or app not yet propagated | copy the key again; wait a few minutes |
| `invalid_grant: invalid assertion` / signature | key and uploaded certificate don't match, or key not PKCS#8 | regenerate both in step 3, upload the new `server.crt` |
| Pub/Sub `error-code=...auth.error` | token or headers rejected | check step 6 works; the service drops the token and retries |
| Pub/Sub `PERMISSION_DENIED` on the topic | user can't read `Return_Requested__e` | permission set assigned? (step 4) |
| REST `404 NOT_FOUND` → dead letter | record deleted, or no FLS on `External_Id__c` | check the record; check the permission set's field permissions |
| REST `400` mentioning `updateOnly` | API version below 61.0 | keep `api-version: "68.0"` |
| `REQUEST_LIMIT_EXCEEDED` in logs | 15,000 REST calls / 24 h used | wait; the replay ID doesn't move, events stay 72 h |
| Container restarts with exit 3 | `-XX:+ExitOnOutOfMemoryError` fired | real finding: record it, raise `mem_limit` or lower `MaxRAMPercentage` |
