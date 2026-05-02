#!/usr/bin/env python3
"""
plot-results.py — Generate empirical bound verification and scalability plots
from JMH JSON output produced by BoundsBench and ComparativeBench.

Usage:
    # Plot everything from a single results file:
    python scripts/plot-results.py docs/results/jmh-results.json

    # Specify which plot type to generate:
    python scripts/plot-results.py docs/results/bounds.json --type bounds
    python scripts/plot-results.py docs/results/comparative.json --type scalability

    # Override output directory:
    python scripts/plot-results.py results.json --out-dir /tmp/plots

Requirements:
    pip install matplotlib

Outputs:
    docs/results/bounds-verification.png
        W/w vs (1 + τ/N) scatter plot.  Theory says data should track or fall
        below the dashed y=x line.

    docs/results/scalability.png
        Side-by-side time and speedup curves for heartbeat vs ForkJoinPool
        vs sequential across carrier-thread counts.
"""

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(
        description="Plot JMH benchmark results for the heartbeat scheduler"
    )
    p.add_argument("results_file", help="JMH JSON results file (-rf json output)")
    p.add_argument(
        "--type",
        choices=["bounds", "scalability", "all"],
        default="all",
        help="Which plot to generate (default: all)",
    )
    p.add_argument(
        "--out-dir",
        default="docs/results",
        help="Output directory for PNG files (default: docs/results)",
    )
    return p.parse_args()


def load_results(path: str) -> list[dict]:
    with open(path) as f:
        data = json.load(f)
    return data if isinstance(data, list) else [data]


def short_method(benchmark_fqn: str) -> str:
    """Return the method name from a fully-qualified JMH benchmark name."""
    return benchmark_fqn.rsplit(".", 1)[-1]


def get_score(entry: dict) -> float:
    return float(entry["primaryMetric"]["score"])


def get_error(entry: dict) -> float:
    return float(entry["primaryMetric"].get("scoreError", 0.0))


# ---------------------------------------------------------------------------
# Plot 1: W/w vs (1 + τ/N)  [BoundsBench]
# ---------------------------------------------------------------------------

def plot_bounds(results: list[dict], out_dir: Path) -> None:
    """Scatter plot verifying the (1 + τ/N) overhead bound empirically."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("ERROR: matplotlib not installed — pip install matplotlib", file=sys.stderr)
        sys.exit(1)

    # Collect BoundsBench scores keyed by (method, ratioNoverTau)
    data: dict[str, dict[int, tuple[float, float]]] = defaultdict(dict)

    for entry in results:
        name = entry["benchmark"]
        if "BoundsBench" not in name:
            continue
        method = short_method(name)
        params = entry.get("params", {})
        ratio_str = params.get("ratioNoverTau", "")
        if not ratio_str:
            continue
        ratio = int(ratio_str)
        data[method][ratio] = (get_score(entry), get_error(entry))

    if "heartbeat" not in data or "sequential" not in data:
        print(
            "WARNING: BoundsBench heartbeat or sequential data missing — skipping bounds plot.",
            file=sys.stderr,
        )
        return

    hb = data["heartbeat"]
    seq = data["sequential"]
    common = sorted(set(hb) & set(seq))
    if not common:
        print("WARNING: No matching ratioNoverTau values found — skipping bounds plot.", file=sys.stderr)
        return

    # Theoretical prediction: 1 + τ/N = 1 + 1/ratio
    x_theory = [1.0 + 1.0 / r for r in common]
    # Measured overhead: heartbeat_time / sequential_time
    y_measured = [hb[r][0] / seq[r][0] for r in common]
    # Propagated error bars (approximate)
    y_err = [
        (hb[r][1] / seq[r][0]) + (hb[r][0] * seq[r][1] / seq[r][0] ** 2)
        for r in common
    ]

    fig, ax = plt.subplots(figsize=(8, 6))

    # Reference line y = x (perfect bound tracking)
    margin = max(x_theory) * 0.12
    ref_end = max(x_theory) + margin
    ax.plot([1.0, ref_end], [1.0, ref_end], "k--", alpha=0.45, linewidth=1.5,
            label="Perfect bound  (y = x)")

    # Measured points with error bars
    ax.errorbar(
        x_theory, y_measured,
        yerr=y_err,
        fmt="o",
        color="#E53935",
        ecolor="#FFCDD2",
        elinewidth=2,
        capsize=5,
        markersize=8,
        zorder=5,
        label="Measured  W/w",
    )

    for ratio, xt, ym in zip(common, x_theory, y_measured):
        ax.annotate(
            f"N={ratio}τ",
            (xt, ym),
            textcoords="offset points",
            xytext=(6, 4),
            fontsize=8,
        )

    ax.set_xlabel("Theoretical bound  (1 + τ/N)", fontsize=12)
    ax.set_ylabel("Measured overhead  W/w  (heartbeat / sequential)", fontsize=12)
    ax.set_title(
        "Empirical verification of the (1 + τ/N) work-overhead bound\n"
        "Data points at or below the dashed line confirm the theoretical prediction.",
        fontsize=11,
    )
    ax.legend(fontsize=10)
    ax.grid(True, linestyle=":", alpha=0.35)
    ax.set_xlim(left=1.0, right=ref_end)
    ax.set_ylim(bottom=0.85)

    out_path = out_dir / "bounds-verification.png"
    plt.tight_layout()
    plt.savefig(out_path, dpi=150)
    print(f"Bounds plot  → {out_path}")
    _print_bounds_summary(common, x_theory, y_measured)


def _print_bounds_summary(ratios, theoretical, measured):
    print()
    print("  ratio N/τ │ predicted (1+τ/N) │ measured W/w │ within bound?")
    print("  " + "-" * 62)
    for r, t, m in zip(ratios, theoretical, measured):
        ok = "✓" if m <= t * 1.20 else "✗"  # 20% tolerance for JIT noise
        print(f"  {r:>9} │ {t:>18.4f} │ {m:>12.4f} │ {ok}")
    print()


# ---------------------------------------------------------------------------
# Plot 2: Scalability curves  [ComparativeBench]
# ---------------------------------------------------------------------------

def plot_scalability(results: list[dict], out_dir: Path) -> None:
    """Execution-time and speedup curves over carrier-thread counts."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("ERROR: matplotlib not installed — pip install matplotlib", file=sys.stderr)
        sys.exit(1)

    # Collect ComparativeBench scores keyed by (method, numCarriers)
    data: dict[str, dict[int, float]] = defaultdict(dict)

    for entry in results:
        name = entry["benchmark"]
        if "ComparativeBench" not in name:
            continue
        method = short_method(name)
        params = entry.get("params", {})
        carriers_str = params.get("numCarriers", "")
        if not carriers_str:
            continue
        data[method][int(carriers_str)] = get_score(entry)

    if not data:
        print(
            "WARNING: ComparativeBench data not found in results — skipping scalability plot.",
            file=sys.stderr,
        )
        return

    all_carriers = sorted({c for ct in data.values() for c in ct})

    # Sequential baseline for speedup computation.
    # Use the sequential method at 1 carrier if available; fall back to max heartbeat time.
    seq = data.get("sequential", {})
    baseline_time = seq.get(1) or (max(seq.values()) if seq else None)
    if baseline_time is None:
        hb = data.get("heartbeat", {})
        baseline_time = max(hb.values()) if hb else 1.0

    METHOD_STYLE = {
        "heartbeat":    ("#2196F3", "o-", "Heartbeat scheduler"),
        "forkJoinPool": ("#4CAF50", "s-", "ForkJoinPool"),
        "sequential":   ("#9E9E9E", "^--", "Sequential"),
    }

    fig, (ax_t, ax_s) = plt.subplots(1, 2, figsize=(13, 5))

    for method, carrier_times in sorted(data.items()):
        carriers = sorted(carrier_times)
        times = [carrier_times[c] for c in carriers]
        speedups = [baseline_time / carrier_times[c] for c in carriers]

        color, fmt, label = METHOD_STYLE.get(method, ("black", "o-", method))
        ax_t.plot(carriers, times, fmt, color=color, label=label, linewidth=2, markersize=7)
        ax_s.plot(carriers, speedups, fmt, color=color, label=label, linewidth=2, markersize=7)

    # Ideal linear speedup line
    ax_s.plot(all_carriers, [float(c) for c in all_carriers],
              "k:", alpha=0.35, linewidth=1.5, label="Ideal linear")

    for ax in (ax_t, ax_s):
        ax.set_xticks(all_carriers)
        ax.grid(True, linestyle=":", alpha=0.35)
        ax.legend(fontsize=9)

    ax_t.set_xlabel("Carrier threads", fontsize=12)
    ax_t.set_ylabel("Execution time (ms)", fontsize=12)
    ax_t.set_title("Execution time vs. parallelism", fontsize=11)

    ax_s.set_xlabel("Carrier threads", fontsize=12)
    ax_s.set_ylabel("Speedup vs. sequential", fontsize=12)
    ax_s.set_title("Scalability curves", fontsize=11)

    fig.suptitle("Fibonacci(35): heartbeat scheduler vs. ForkJoinPool", fontsize=12, y=1.02)

    out_path = out_dir / "scalability.png"
    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    print(f"Scalability plot → {out_path}")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    args = parse_args()
    results_path = Path(args.results_file)
    if not results_path.exists():
        print(f"ERROR: File not found: {results_path}", file=sys.stderr)
        sys.exit(1)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Loading JMH results from {results_path} …")
    results = load_results(str(results_path))
    print(f"  {len(results)} benchmark entries")

    if args.type in ("bounds", "all"):
        plot_bounds(results, out_dir)

    if args.type in ("scalability", "all"):
        plot_scalability(results, out_dir)


if __name__ == "__main__":
    main()
