#!/usr/bin/env python3
"""
visualize-jfr.py — Gantt-chart visualizer for heartbeat scheduler JFR recordings.

Usage:
    python scripts/visualize-jfr.py recording.jfr [--out docs/results/promotions.png]

Requirements:
    pip install matplotlib

The script calls `jfr print --json <recording.jfr>` to dump all events as JSON,
then parses the three custom heartbeat event types and renders:

  1. A Gantt-style chart: promotion events per carrier thread over time.
  2. A secondary bar chart: total promotions per carrier thread.

The JFR recording is typically produced by:
    java -XX:StartFlightRecording=filename=recording.jfr,duration=30s \
         --add-exports java.base/jdk.internal.vm=ALL-UNNAMED \
         -cp target/classes \
         org.heartbeat.scheduler.examples.FibonacciExample 35
"""

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(description="Visualize heartbeat scheduler JFR events")
    p.add_argument("jfr_file", help="Path to the .jfr recording file")
    p.add_argument(
        "--out",
        default="docs/results/promotions.png",
        help="Output PNG path (default: docs/results/promotions.png)",
    )
    p.add_argument(
        "--min-duration-ns",
        type=int,
        default=0,
        help="Minimum JoinBlocked duration (ns) to display (default: 0 = all)",
    )
    return p.parse_args()


def load_events(jfr_file: str) -> list[dict]:
    """Invoke `jfr print --json` and return parsed event list."""
    result = subprocess.run(
        ["jfr", "print", "--json", jfr_file],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        print(f"ERROR: jfr print failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)

    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        print(f"ERROR: Could not parse jfr output as JSON: {e}", file=sys.stderr)
        sys.exit(1)

    # jfr print --json wraps events under {"recording": {"events": [...]}}
    if isinstance(data, dict) and "recording" in data:
        return data["recording"].get("events", [])
    # Fallback: data is already a list
    if isinstance(data, list):
        return data
    return []


def extract_heartbeat_events(events: list[dict]) -> dict:
    """Split events by type into categorised lists."""
    promotions = []
    poll_checks = []
    join_blocked = []

    for ev in events:
        name = ev.get("type", {}).get("name", "") or ev.get("name", "")
        values = ev.get("values", ev)  # some jfr versions inline values at top level

        if "Promotion" in name and "PollCheck" not in name:
            promotions.append({
                "carrier": values.get("carrier", "unknown"),
                "frameAgeNanos": int(values.get("frameAgeNanos", 0)),
                "framesInFlight": int(values.get("framesInFlight", 0)),
                "startTime": _parse_time(ev.get("startTime", "0")),
            })
        elif "PollCheck" in name:
            poll_checks.append({
                "totalPolls": int(values.get("totalPolls", 0)),
                "totalPromotions": int(values.get("totalPromotions", 0)),
                "startTime": _parse_time(ev.get("startTime", "0")),
            })
        elif "JoinBlocked" in name:
            join_blocked.append({
                "carrier": values.get("carrier", "unknown"),
                "taskAgeNanos": int(values.get("taskAgeNanos", 0)),
                "startTime": _parse_time(ev.get("startTime", "0")),
                "duration": _parse_duration(ev.get("duration", "0 ns")),
            })

    return {
        "promotions": promotions,
        "poll_checks": poll_checks,
        "join_blocked": join_blocked,
    }


def _parse_time(value) -> float:
    """Convert JFR timestamp to float nanoseconds."""
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    # Remove units if present (e.g., "12345678 ns")
    s = s.split()[0]
    try:
        return float(s)
    except ValueError:
        return 0.0


def _parse_duration(value) -> float:
    """Convert JFR duration string to nanoseconds."""
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    if s.endswith("ms"):
        return float(s[:-2]) * 1_000_000
    if s.endswith("us") or s.endswith("μs"):
        return float(s.rstrip("sμu")) * 1_000
    if s.endswith("ns"):
        return float(s[:-2])
    if s.endswith("s"):
        return float(s[:-1]) * 1_000_000_000
    try:
        return float(s)
    except ValueError:
        return 0.0


def plot(data: dict, out_path: str) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import matplotlib.patches as mpatches
    except ImportError:
        print("ERROR: matplotlib not installed.  pip install matplotlib", file=sys.stderr)
        sys.exit(1)

    promotions = data["promotions"]
    join_blocked = data["join_blocked"]

    if not promotions and not join_blocked:
        print("WARNING: No heartbeat events found in the recording.", file=sys.stderr)
        print("  Make sure you recorded with the heartbeat scheduler running.", file=sys.stderr)
        print("  Generating an empty placeholder chart.", file=sys.stderr)

    # --- normalise timestamps to milliseconds from first event ---
    all_times = [e["startTime"] for e in promotions + join_blocked]
    t0 = min(all_times) if all_times else 0.0

    def to_ms(ns: float) -> float:
        return (ns - t0) / 1_000_000.0

    # Gather unique carriers
    carriers = sorted(set(
        e["carrier"] for e in promotions + join_blocked
    )) or ["(no events)"]
    carrier_idx = {c: i for i, c in enumerate(carriers)}
    n_carriers = len(carriers)

    fig, (ax_gantt, ax_count) = plt.subplots(
        2, 1,
        figsize=(14, 3 + n_carriers * 0.8),
        gridspec_kw={"height_ratios": [3, 1]},
    )

    # ---- Gantt chart (top) ----
    promo_color = "#2196F3"
    blocked_color = "#F44336"

    for ev in promotions:
        y = carrier_idx[ev["carrier"]]
        x = to_ms(ev["startTime"])
        ax_gantt.scatter(x, y, color=promo_color, s=20, zorder=3, linewidths=0)

    for ev in join_blocked:
        y = carrier_idx[ev["carrier"]]
        x_start = to_ms(ev["startTime"])
        width = ev["duration"] / 1_000_000.0  # ns → ms
        ax_gantt.barh(y, width, left=x_start, height=0.5,
                      color=blocked_color, alpha=0.6)

    ax_gantt.set_yticks(range(n_carriers))
    ax_gantt.set_yticklabels(carriers, fontsize=8)
    ax_gantt.set_xlabel("Time (ms)")
    ax_gantt.set_ylabel("Carrier thread")
    ax_gantt.set_title("Heartbeat Scheduler — Promotion & Join-Blocked timeline")
    ax_gantt.grid(axis="x", linestyle=":", alpha=0.5)

    legend_patches = [
        mpatches.Patch(color=promo_color, label="Promotion"),
        mpatches.Patch(color=blocked_color, alpha=0.6, label="Join blocked"),
    ]
    ax_gantt.legend(handles=legend_patches, loc="upper right", fontsize=8)

    # ---- Bar chart: promotions per carrier (bottom) ----
    promo_counts: dict[str, int] = defaultdict(int)
    for ev in promotions:
        promo_counts[ev["carrier"]] += 1

    ax_count.bar(
        [carrier_idx[c] for c in carriers],
        [promo_counts.get(c, 0) for c in carriers],
        color=promo_color,
    )
    ax_count.set_xticks(range(n_carriers))
    ax_count.set_xticklabels(carriers, rotation=30, ha="right", fontsize=7)
    ax_count.set_ylabel("Promotions")
    ax_count.set_title("Promotions per carrier thread")
    ax_count.grid(axis="y", linestyle=":", alpha=0.5)

    plt.tight_layout()
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out, dpi=150)
    print(f"Chart written to {out}")

    # Print summary stats
    print(f"\nSummary:")
    print(f"  Promotion events  : {len(promotions)}")
    print(f"  Poll-check events : {len(data['poll_checks'])}")
    print(f"  Join-blocked evts : {len(join_blocked)}")
    print(f"  Carriers observed : {n_carriers}")
    if promotions:
        ages = [e["frameAgeNanos"] for e in promotions]
        print(f"  Frame age (ns)    : min={min(ages):,}  median={sorted(ages)[len(ages)//2]:,}  max={max(ages):,}")


def main():
    args = parse_args()
    if not Path(args.jfr_file).exists():
        print(f"ERROR: File not found: {args.jfr_file}", file=sys.stderr)
        sys.exit(1)

    print(f"Loading events from {args.jfr_file} …")
    events = load_events(args.jfr_file)
    print(f"  Total events in recording: {len(events)}")

    data = extract_heartbeat_events(events)
    plot(data, args.out)


if __name__ == "__main__":
    main()
