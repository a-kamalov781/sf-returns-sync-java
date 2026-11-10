#!/usr/bin/env bash
# Samples `docker stats` of the compose "service" container for DURATION seconds (default 1 h, every 60 s),
# writes a CSV and prints min / avg / max / last plus a line ready for the README "Measured" table.
#
# Usage (from the repo root, with `docker compose up -d` running):
#   scripts/measure-memory.sh
#   DURATION=600 INTERVAL=10 scripts/measure-memory.sh      # quick 10-minute check
set -euo pipefail

DURATION="${DURATION:-3600}"
INTERVAL="${INTERVAL:-60}"
OUT_DIR="${OUT_DIR:-measurements}"   # git-ignored

id="$(docker compose ps -q service)"
[[ -n "$id" ]] || { echo "compose service 'service' is not running (docker compose up -d)" >&2; exit 1; }
limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$id")"

mkdir -p "$OUT_DIR"
csv="$OUT_DIR/memory-$(date -u +%Y%m%dT%H%M%S).csv"
echo "utc,mem_mib,mem_usage,mem_perc" > "$csv"

# "152.3MiB / 384MiB" -> 152.3
to_mib() {
  awk '{ v = $1; u = v; gsub(/[0-9.]/, "", u); gsub(/[^0-9.]/, "", v);
         if (u == "KiB" || u == "kB") v /= 1024; else if (u == "GiB" || u == "GB") v *= 1024; else if (u == "B") v /= 1048576;
         printf "%.1f", v }' <<<"$1"
}

samples=$((DURATION / INTERVAL))
echo "Sampling container ${id:0:12} every ${INTERVAL}s for ${DURATION}s ($samples samples) -> $csv"
for ((i = 1; i <= samples; i++)); do
  line="$(docker stats --no-stream --format '{{.MemUsage}},{{.MemPerc}}' "$id")"
  usage="${line%,*}"
  mib="$(to_mib "$usage")"
  echo "$(date -u +%FT%TZ),$mib,\"$usage\",${line##*,}" >> "$csv"
  printf '\r%4d/%d  %s   ' "$i" "$samples" "$usage"
  (( i < samples )) && sleep "$INTERVAL"
done
echo

state="$(docker inspect --format 'restarts={{.RestartCount}} oomkilled={{.State.OOMKilled}} running={{.State.Running}}' "$id")"
summary="$(awk -F, 'NR > 1 { v = $2 + 0; s += v; n++; if (n == 1 || v < mn) mn = v; if (v > mx) mx = v; last = v }
                    END { printf "min=%.1f avg=%.1f max=%.1f last=%.1f MiB (n=%d)", mn, s / n, mx, last, n }' "$csv")"
echo "$summary; container limit $((limit / 1048576)) MiB; $state"
echo
echo "README 'Measured' row (fill in the event count from scripts/load-test.sh):"
max="$(awk -F, 'NR > 1 { v = $2 + 0; if (v > m) m = v } END { printf "%.1f", m }' "$csv")"
last="$(tail -1 "$csv" | cut -d, -f2)"
echo "| Memory after $((DURATION / 60)) min with a live org (<N> events) | max ${max} MiB, last ${last} MiB / $((limit / 1048576)) MiB | scripts/measure-memory.sh, $(date -u +%F) |"
