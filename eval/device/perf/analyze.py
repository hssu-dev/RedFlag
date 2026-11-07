"""Frame stats for dev.redflag.app from a perfetto trace (eval/device/perf/run_perf.sh).

Usage: python analyze.py trace.pftrace
Prints: frames, frames > 33 ms / > 50 ms / > 100 ms, longest frame, and the RenderThread time spent asleep in
queueBuffer (waiting on the GPU) — the stall seen while Gemma runs on the GPU (2026-11-05)."""
import json, sys
from perfetto.trace_processor import TraceProcessor

tp = TraceProcessor(trace=sys.argv[1])
q = lambda s: tp.query(s).as_pandas_dataframe()
up = q("select upid from process where name='dev.redflag.app'")
if up.empty:
    sys.exit("dev.redflag.app not in trace")
up = int(up.upid.iloc[0])
threads = q(f"select utid, name, is_main_thread from thread where upid={up}")
main = int(threads[threads.is_main_thread == 1].utid.iloc[0])
frames = q(f"""select sl.dur from slice sl join thread_track tt on sl.track_id=tt.id
  where tt.utid={main} and sl.depth=0 and sl.name like 'Choreographer#doFrame%'""")
rt = threads[threads.name == 'RenderThread']
wait = 0.0
if not rt.empty:
    w = q(f"""select sum(sl.dur) d from slice sl join thread_track tt on sl.track_id=tt.id
      where tt.utid={int(rt.utid.iloc[0])} and sl.name='waitForever'""")
    wait = float(w.d.iloc[0] or 0) / 1e6
ex = threads[threads.name.str.startswith('execution_threa', na=False)]
gemma = 0.0
if not ex.empty:
    gemma = float(q(f"select sum(dur) d from sched where utid={int(ex.utid.iloc[0])}").d.iloc[0] or 0) / 1e6
main_cpu = float(q(f"select sum(dur) d from sched where utid={main}").d.iloc[0] or 0) / 1e6
d = frames.dur / 1e6
print(json.dumps({
    "frames": int(len(d)), "over33ms": int((d > 33).sum()), "over50ms": int((d > 50).sum()), "over100ms": int((d > 100).sum()),
    "max_ms": round(float(d.max()), 1) if len(d) else 0, "p99_ms": round(float(d.quantile(0.99)), 1) if len(d) else 0,
    "gpu_wait_ms": round(wait), "gemma_thread_cpu_ms": round(gemma), "main_cpu_ms": round(main_cpu),
}))
