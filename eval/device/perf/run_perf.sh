#!/bin/sh
# One measured check on the phone: perfetto trace while a shared text is checked in the chat home.
# usage: run_perf.sh <serial> <out.pftrace> "<text to share>" [seconds]
set -e
S="$1"; OUT="$2"; TEXT="$3"; SECS="${4:-22}"
DIR="$(cd "$(dirname "$0")" && pwd)"
cat > /tmp/rf_perf.cfg <<CFG
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources: { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup"
  atrace_categories: "view" atrace_categories: "gfx" atrace_apps: "dev.redflag.app"
} } }
data_sources: { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
duration_ms: ${SECS}000
CFG
adb -s "$S" push /tmp/rf_perf.cfg /data/local/tmp/rf_perf.cfg >/dev/null
adb -s "$S" shell "cat /data/local/tmp/rf_perf.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/rf_perf.pftrace" >/dev/null 2>&1 &
sleep 2
adb -s "$S" shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$TEXT' -n dev.redflag.app/.check.ShareEntryActivity" >/dev/null
wait
adb -s "$S" pull /data/misc/perfetto-traces/rf_perf.pftrace "$OUT" >/dev/null
