# Load test and memory measurement

Goal: one hour of steady event traffic from a live Developer Edition org, and the real `docker stats` numbers
for the README *Measured* table. Prerequisite: [ORG_SETUP.md](ORG_SETUP.md) steps 1–8 pass.

## Budget: Developer Edition limits

Official allocations, v68.0 (Winter '27). All are rolling windows and shared with anything else you run in
the org that day.

| Limit | Developer Edition | One default run (1,200 events) uses |
|---|---|---|
| Event delivery to Pub/Sub API clients | 10,000 / 24 h | 1,200 (one subscriber) |
| Event publishing | 50,000 / hour | 1,200 |
| API requests (Total Calls Per 24-Hour Period) | 15,000 / 24 h | ~1,200 REST PATCH from the service + ~15 CLI calls + a few token requests |
| Data storage | 5 MB, most records count as 2 KB → ~2,500 records | 1,200 records ≈ 2.4 MB until cleanup |
| Anonymous Apex per round | 10,000 DML rows, 150 DML statements | ≤ 1,000 rows, ≤ 10 statements |

So: at most one default run per day, plus the ORG_SETUP checklist. `scripts/load-test.sh` refuses more than
`MAX_TOTAL=2000` records per run. Pub/Sub delivery is counted per subscriber: a second service instance on the
same topic doubles delivery usage.

Check what's left before and after (the script prints it too):

```bash
sf limits api display --target-org returns-dev | grep -iE 'DailyApiRequests|PlatformEvents|DataStorageMB'
```

## Run it (two terminals, from the repo root)

**Terminal 1: memory sampler** (60 samples, one per minute):

```bash
scripts/measure-memory.sh
```

**Terminal 2: event stream**, started right after the sampler. Defaults: 12 rounds × 100 records, every 300 s:

```bash
ORG=returns-dev scripts/load-test.sh
```

Each round runs `scripts/load-test.apex` through `sf apex run`: it inserts the records in chunks of 200, so the
trigger publishes with one `EventBus.publish` per chunk. Amounts vary, so you get both `Approved` and
`Manual_Review`. Records are tagged `LOAD-<run>-<round>-<i>` in `Order_Number__c`.

Smaller or burstier variants:

```bash
ORG=returns-dev ROUNDS=6 PER_ROUND=50 INTERVAL=600 scripts/load-test.sh
ORG=returns-dev ROUNDS=2 PER_ROUND=500 INTERVAL=1800 scripts/load-test.sh
```

A 500-record round arrives as bursts of up to `batch-size` (25) events per `FetchRequest`, which is the case
worth watching for memory and for the 60-second rule.

## While it runs

```bash
docker compose logs -f service | grep -vE 'Duplicate'
```

- No `Batch failed`, no `Reconnecting in` (except after a deliberate restart).
- No `Transient error`, or only a few that succeed on retry.

## After it runs

1. Let the service drain (a minute), then check that every record got a status and nothing is duplicated:

```bash
sf data query --target-org returns-dev --query "SELECT Status__c, COUNT(Id) n FROM Return_Request__c WHERE Order_Number__c LIKE 'LOAD-%' GROUP BY Status__c"
docker compose exec db psql -U returns -d returns -c "SELECT count(*) AS rows, count(DISTINCT external_id) AS distinct_ids FROM processed_returns" -c "SELECT count(*) AS dead_letters FROM dead_letters"
```

Expected: no `New` left, `rows = distinct_ids`, `dead_letters = 0`.

2. Latency (record created → processed), from the service side:

```bash
docker compose exec db psql -U returns -d returns -c "SELECT min(processed_at), max(processed_at), count(*) FROM processed_returns WHERE processed_at > now() - interval '2 hours'"
```

For a per-record figure, compare a few records' `CreatedDate` (`sf data query ... SELECT External_Id__c, CreatedDate ...`)
with `processed_at` for the same `external_id`.

3. Write the numbers into the README. `measure-memory.sh` prints a ready table row at the end, e.g.:

```
| Memory after 60 min with a live org (<N> events) | max 171.4 MiB, last 160.2 MiB / 384 MiB | scripts/measure-memory.sh, 2026-11-12 |
```

Replace `<N>` with the event count, replace the *"Memory after 1 h with a live org"* row in README → *Measured*,
fill in the latency row, and move the memory item out of *Not verified yet*. The CSV stays in `measurements/`
(git-ignored); attach it to a GitHub issue or release if you want to keep it.

4. Clean up the records (storage), once nothing is `New` any more:

```bash
sf apex run --file scripts/load-test-cleanup.apex --target-org returns-dev
```

Run it again until it prints `left=0` (one run removes up to 4,500).

## What to record if something goes wrong

- Container restarted, `oomkilled=true`, or exit code 3 (`ExitOnOutOfMemoryError`): that is a result, not a
  failure of the test. Note the max MiB and the event rate, then try `MaxRAMPercentage=60` or `mem_limit: 448m`.
- `REQUEST_LIMIT_EXCEEDED`: the API budget ran out. The service keeps the replay ID and backs off; events wait up
  to 72 h on the bus. Note it and continue the next day.
- `Reconnecting in ...` with no `Subscribe stream error` before it: the server closed the stream, most likely
  because a batch took longer than 60 s. Note the batch size and REST latency in the log.
