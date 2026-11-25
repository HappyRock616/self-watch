"""Overnight watch test report from a Pixel 5 pull (tools/overnight/pull.sh).

  python tools/overnight/analyze.py <pull-dir> <start ISO UTC> <end ISO UTC>

Reads watch.log (Self Watch's :watch process log) and selfwatch.db. Prints Markdown:
watch battery and drain, link and back-off, syncs and bytes, watch web requests, and the
sampling intervals the watch actually recorded (from synced data, not from settings).
Standard library only.
"""
import datetime as dt
import re
import sqlite3
import statistics
import sys
from collections import Counter, defaultdict

METRICS = {1: "heart rate", 2: "HRV (rmssd)", 3: "SpO2", 4: "respiration", 5: "stress", 6: "skin temp delta",
           7: "resting HR", 8: "resting kcal", 9: "BP systolic", 10: "BP diastolic", 11: "energy level"}
LINE = re.compile(r"^(\S+Z) (\w)/(\S+): (.*)$")


def ts(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def fmt(sec):
    return dt.datetime.fromtimestamp(sec, dt.timezone.utc).strftime("%H:%M:%SZ")


def log_lines(path, t0, t1):
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if m:
                t = ts(m.group(1))
                if t0 <= t <= t1:
                    out.append((t, m.group(3), m.group(4)))
    return out


def intervals(times):
    times = sorted(times)
    return [b - a for a, b in zip(times, times[1:])]


def describe(gaps):
    if not gaps:
        return "-"
    return f"median {statistics.median(gaps):.0f} s, min {min(gaps):.0f} s, max {max(gaps):.0f} s"


def main():
    d, t0, t1 = sys.argv[1], ts(sys.argv[2]), ts(sys.argv[3])
    hours = (t1 - t0) / 3600
    lines = log_lines(f"{d}/watch.log", t0, t1)
    print(f"# Overnight report {fmt(t0)} to {fmt(t1)} ({hours:.1f} h)\n")

    print("## Watch battery")
    batt = [(t, int(re.search(r"(\d+)%", msg).group(1))) for t, tag, msg in lines if msg.startswith("watch battery ")]
    for t, p in batt:
        print(f"- {fmt(t)}: {p}%")
    if len(batt) >= 2:
        (ta, pa), (tb, pb) = batt[0], batt[-1]
        rate = (pa - pb) / max((tb - ta) / 3600, 1e-9)
        print(f"- **Drain: {pa - pb} points in {(tb - ta) / 3600:.1f} h = {rate:.2f} %/h** (pass < 1 %/h; Garmin spec about 0.4 %/h)")
    print()

    print("## Link")
    ups = [t for t, tag, msg in lines if msg.startswith("connected, mtu")]
    downs = [(t, msg) for t, tag, msg in lines if msg.startswith("link down")]
    retries = [int(re.search(r"retry in (\d+) s", msg).group(1)) for t, tag, msg in lines if "disconnected; retry in" in msg]
    timeouts = sum(1 for _, _, msg in lines if msg == "connect timed out")
    print(f"- Links up: {len(ups)}; links down: {len(downs)} ({dict(Counter(re.sub(r' state.*', '', m) for _, m in downs))}); connect timeouts: {timeouts}")
    print(f"- Reconnect waits chosen (s): {retries or '-'}")
    fast = [r for r in retries if r < 5]
    print(f"- Waits under the 5 s floor: {len(fast)} (must be 0)")
    contested = [fmt(t) for t, tag, msg in lines if "keeps dropping soon after connecting" in msg]
    print(f"- Contested-watch detections: {contested or 'none'}")
    prio = Counter(msg for _, _, msg in lines if msg.startswith("connection priority"))
    print(f"- Connection priority: {dict(prio) or '-'}")
    print()

    print("## Syncs")
    starts = Counter(re.search(r"\((\w+)\)", msg).group(1) for _, _, msg in lines if msg.startswith("sync started"))
    done = [msg for _, _, msg in lines if msg.startswith("sync done")]
    print(f"- Started by trigger: {dict(starts) or '-'}; finished: {len(done)}")
    con = sqlite3.connect(f"file:{d}/selfwatch.db?mode=ro", uri=True)
    rows = con.execute("SELECT file_type, file_subtype, COUNT(*), SUM(size) FROM sync_file WHERE done_sec BETWEEN ? AND ? "
                       "GROUP BY file_type, file_subtype", (t0, t1)).fetchall()
    total = sum(r[3] or 0 for r in rows)
    for ft, st, n, size in rows:
        print(f"- File type {ft}/{st}: {n} files, {size or 0:,} bytes")
    print(f"- Total moved: {total:,} bytes\n")

    print("## Watch requests and replies")
    req = Counter()
    for _, tag, msg in lines:
        if tag in ("WebRequestRouter", "garmin.weather") or msg.startswith("watch asks"):
            req[re.sub(r"\d+ bytes", "n bytes", msg)] += 1
    for msg, n in req.most_common():
        print(f"- {n} x {msg}")
    gps = [t for t, tag, msg in lines if "GPS_ASSIST" in msg]
    print(f"- GPS-assist request spacing: {describe(intervals(gps))}")
    print()

    print("## Sampling intervals recorded by the watch (from synced data)")
    print("| Data | Samples | Interval |")
    print("|---|---|---|")
    by = defaultdict(list)
    for kind, t in con.execute("SELECT kind, t_sec FROM metric WHERE t_sec BETWEEN ? AND ? AND import_id IS NULL", (t0, t1)):
        by[kind].append(t)
    for kind in sorted(by):
        print(f"| {METRICS.get(kind, kind)} | {len(by[kind])} | {describe(intervals(by[kind]))} |")
    mins = [t for (t,) in con.execute("SELECT t_sec FROM activity_minute WHERE t_sec BETWEEN ? AND ? AND import_id IS NULL", (t0, t1))]
    print(f"| activity (steps/intensity) | {len(mins)} | {describe(intervals(mins))} |")
    stages = [t for (t,) in con.execute("SELECT t_sec FROM sleep_stage WHERE t_sec BETWEEN ? AND ? AND import_id IS NULL", (t0, t1))]
    print(f"| sleep stages | {len(stages)} | {describe(intervals(stages))} |")
    print()
    print("Phone-side timers in the :watch process (from code): day summary every 15 min (SummaryBuilder.TICK_SEC);")
    print("music updates at most 1/s and only on change; live HR lease only while a live card is open;")
    print("CaloriesLeft only when a watch app is chosen (off); reconnect back-off 5 s to 5 min.")


if __name__ == "__main__":
    main()
