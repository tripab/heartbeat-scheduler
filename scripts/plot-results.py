#!/usr/bin/env python3
"""
plot-results.py — Generate empirical bound verification, scalability, and PBBS
plots from JMH JSON output.

Usage:
    # Plot everything from a single results file:
    python scripts/plot-results.py docs/results/jmh-results.json

    # Specify which plot type to generate:
    python scripts/plot-results.py docs/results/bounds.json --type bounds
    python scripts/plot-results.py docs/results/comparative.json --type scalability
    python scripts/plot-results.py docs/results/pbbs.json --type pbbs

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

    docs/results/pbbs-times.png
        Per-PBBS-workload time bars for sequential, ForkJoinPool, and Heartbeat.

    docs/results/pbbs-heartbeat-vs-forkjoin.png
        Heartbeat / ForkJoinPool time ratio per PBBS workload and parameter set.
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
        choices=["bounds", "scalability", "pbbs", "all"],
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


def short_class(benchmark_fqn: str) -> str:
    """Return the benchmark class name from a fully-qualified JMH benchmark name."""
    class_fqn = benchmark_fqn.rsplit(".", 1)[0]
    return class_fqn.rsplit(".", 1)[-1]


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
# Plot 3: PBBS Java mode comparisons [Pbbs*Bench]
# ---------------------------------------------------------------------------

def plot_pbbs(results: list[dict], out_dir: Path) -> None:
    """PBBS per-workload mode bars and Heartbeat/ForkJoin ratios."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("ERROR: matplotlib not installed — pip install matplotlib", file=sys.stderr)
        sys.exit(1)

    groups = _collect_pbbs_groups(results)
    if not groups:
        print("WARNING: Pbbs*Bench data not found in results — skipping PBBS plots.", file=sys.stderr)
        return

    labels = [_pbbs_label(key) for key in groups]
    methods = ["sequential", "forkJoinPool", "heartbeat"]
    style = {
        "sequential": ("#9E9E9E", "Sequential"),
        "forkJoinPool": ("#4CAF50", "ForkJoinPool"),
        "heartbeat": ("#2196F3", "Heartbeat"),
    }

    # Per-workload mode time bars.
    x = list(range(len(groups)))
    width = 0.24
    fig_width = max(10, len(groups) * 1.3)
    fig, ax = plt.subplots(figsize=(fig_width, 5.5))

    for offset, method in enumerate(methods):
        positions = [i + (offset - 1) * width for i in x]
        scores = [groups[key].get(method, 0.0) for key in groups]
        color, label = style[method]
        bars = ax.bar(positions, scores, width=width, color=color, label=label)
        for bar, score in zip(bars, scores):
            if score == 0.0:
                bar.set_alpha(0.20)

    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=35, ha="right", fontsize=8)
    ax.set_ylabel("Execution time (ms/op)", fontsize=12)
    ax.set_title("PBBS-style Java benchmark modes", fontsize=12)
    ax.grid(True, axis="y", linestyle=":", alpha=0.35)
    ax.legend(fontsize=9)

    out_path = out_dir / "pbbs-times.png"
    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    print(f"PBBS time bars → {out_path}")

    _plot_pbbs_ratio(groups, labels, out_dir, plt)
    _print_pbbs_summary(groups)


def _collect_pbbs_groups(results: list[dict]) -> dict[tuple[str, str, str, str], dict[str, float]]:
    groups: dict[tuple[str, str, str, str], dict[str, float]] = defaultdict(dict)

    for entry in results:
        name = entry["benchmark"]
        bench_class = short_class(name)
        if not bench_class.startswith("Pbbs") or not bench_class.endswith("Bench"):
            continue
        method = short_method(name)
        params = entry.get("params", {})
        key = (
            bench_class,
            params.get("distribution", ""),
            params.get("size", ""),
            params.get("threshold", ""),
        )
        groups[key][method] = get_score(entry)

    return dict(sorted(groups.items(), key=lambda item: item[0]))


def _pbbs_label(key: tuple[str, str, str, str]) -> str:
    bench_class, distribution, size, threshold = key
    family = bench_class.removeprefix("Pbbs").removesuffix("Bench")
    parts = [family]
    if distribution:
        parts.append(distribution)
    if size:
        parts.append(f"n={size}")
    if threshold:
        parts.append(f"t={threshold}")
    return "\n".join(parts)


def _plot_pbbs_ratio(groups: dict[tuple[str, str, str, str], dict[str, float]],
                     labels: list[str], out_dir: Path, plt) -> None:
    ratios: list[float] = []
    ratio_labels: list[str] = []
    for key, scores in groups.items():
        hb = scores.get("heartbeat")
        fj = scores.get("forkJoinPool")
        if hb is None or fj is None or fj == 0.0:
            continue
        ratios.append(hb / fj)
        ratio_labels.append(_pbbs_label(key))

    if not ratios:
        print("WARNING: PBBS Heartbeat/ForkJoin pairs missing — skipping ratio plot.", file=sys.stderr)
        return

    x = list(range(len(ratios)))
    fig_width = max(10, len(ratios) * 1.3)
    fig, ax = plt.subplots(figsize=(fig_width, 4.8))
    colors = ["#2196F3" if ratio <= 1.0 else "#E53935" for ratio in ratios]
    ax.bar(x, ratios, color=colors, width=0.65)
    ax.axhline(1.0, color="black", linestyle="--", linewidth=1.2, alpha=0.45)
    ax.set_xticks(x)
    ax.set_xticklabels(ratio_labels, rotation=35, ha="right", fontsize=8)
    ax.set_ylabel("Heartbeat time / ForkJoinPool time", fontsize=12)
    ax.set_title("PBBS Heartbeat vs. ForkJoinPool ratio", fontsize=12)
    ax.grid(True, axis="y", linestyle=":", alpha=0.35)

    out_path = out_dir / "pbbs-heartbeat-vs-forkjoin.png"
    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    print(f"PBBS ratio plot → {out_path}")


def _print_pbbs_summary(groups: dict[tuple[str, str, str, str], dict[str, float]]) -> None:
    print()
    print("  PBBS benchmark │ distribution │ size │ threshold │ seq ms │ fj ms │ hb ms │ hb/fj")
    print("  " + "-" * 94)
    for key, scores in groups.items():
        bench_class, distribution, size, threshold = key
        family = bench_class.removeprefix("Pbbs").removesuffix("Bench")
        seq = scores.get("sequential")
        fj = scores.get("forkJoinPool")
        hb = scores.get("heartbeat")
        ratio = hb / fj if hb is not None and fj not in (None, 0.0) else None
        print(
            f"  {family:<14} │ {distribution or '-':<12} │ {size or '-':>4} │ "
            f"{threshold or '-':>9} │ {_fmt_score(seq):>6} │ {_fmt_score(fj):>5} │ "
            f"{_fmt_score(hb):>5} │ {_fmt_score(ratio):>5}"
        )
    print()


def _fmt_score(value: float | None) -> str:
    return "-" if value is None else f"{value:.3f}"


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

    if args.type in ("pbbs", "all"):
        plot_pbbs(results, out_dir)


if __name__ == "__main__":
    main()
