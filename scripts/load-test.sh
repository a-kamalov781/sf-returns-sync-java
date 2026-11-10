#!/usr/bin/env bash
# Steady stream of Return_Requested__e events for the memory/latency measurement.
# Inserts PER_ROUND Return_Request__c records every INTERVAL seconds, ROUNDS times, via anonymous Apex.
# Defaults: 12 rounds x 100 records every 300 s = 1,200 events over one hour.
#
# Developer Edition budget (see docs/LOAD_TEST.md): 10,000 event deliveries / 24 h, 50,000 publishes / h,
# 15,000 API calls / 24 h (each event = 1 REST PATCH from the service), 5 MB data storage (~2,500 records).
# The script refuses more than MAX_TOTAL records per run.
#
# Usage (from the repo root, Git Bash / macOS / Linux):
#   ORG=returns-dev scripts/load-test.sh
#   ORG=returns-dev ROUNDS=6 PER_ROUND=50 INTERVAL=600 scripts/load-test.sh
set -euo pipefail

ORG="${ORG:-returns-dev}"
ROUNDS="${ROUNDS:-12}"
PER_ROUND="${PER_ROUND:-100}"
INTERVAL="${INTERVAL:-300}"
MAX_TOTAL="${MAX_TOTAL:-2000}"

here="$(cd "$(dirname "$0")" && pwd)"
template="$here/load-test.apex"
run_id="r$(date -u +%Y%m%dT%H%M%S)"
total=$((ROUNDS * PER_ROUND))

command -v sf >/dev/null || { echo "Salesforce CLI 'sf' not found (npm install --global @salesforce/cli)" >&2; exit 1; }
[[ "$PER_ROUND" =~ ^[0-9]+$ && "$PER_ROUND" -ge 1 && "$PER_ROUND" -le 1000 ]] || { echo "PER_ROUND must be 1..1000" >&2; exit 1; }
[[ "$ROUNDS" =~ ^[0-9]+$ && "$ROUNDS" -ge 1 ]] || { echo "ROUNDS must be >= 1" >&2; exit 1; }
if (( total > MAX_TOTAL )); then
  echo "Refusing $total records (> MAX_TOTAL=$MAX_TOTAL). Developer Edition storage is ~2,500 records;" >&2
  echo "run scripts/load-test-cleanup.apex between runs, or raise MAX_TOTAL if you know the org has room." >&2
  exit 1
fi

limits() {
  echo "--- org limits ($1) ---"
  sf limits api display --target-org "$ORG" 2>/dev/null \
    | grep -iE 'DailyApiRequests|PlatformEvents|DataStorageMB' || echo "(could not read limits)"
}

echo "Run $run_id: $ROUNDS rounds x $PER_ROUND records every ${INTERVAL}s = $total events, org '$ORG'"
limits "before"

tmp="$(mktemp "${TMPDIR:-/tmp}/load-test-XXXXXX.apex")"
trap 'rm -f "$tmp"' EXIT

for ((round = 1; round <= ROUNDS; round++)); do
  tag="$run_id-$round"
  sed -e "s/^Integer n = [0-9]*;/Integer n = $PER_ROUND;/" \
      -e "s/^String runTag = '[^']*';/String runTag = '$tag';/" "$template" > "$tmp"
  started="$(date -u +%H:%M:%S)"
  if out="$(sf apex run --file "$tmp" --target-org "$ORG" 2>&1)" && grep -q "Executed successfully" <<<"$out"; then
    echo "$started round $round/$ROUNDS: inserted $PER_ROUND (tag $tag)"
  else
    echo "$started round $round/$ROUNDS FAILED:" >&2
    echo "$out" | tail -20 >&2
    exit 1
  fi
  (( round < ROUNDS )) && sleep "$INTERVAL"
done

limits "after"
echo "--- status of this run's records (give the service a minute to finish) ---"
sf data query --target-org "$ORG" --query \
  "SELECT Status__c, COUNT(Id) n FROM Return_Request__c WHERE Order_Number__c LIKE 'LOAD-$run_id-%' GROUP BY Status__c"
echo "When everything is Approved / Manual_Review: sf apex run --file scripts/load-test-cleanup.apex --target-org $ORG"
