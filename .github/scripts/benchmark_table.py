#!/usr/bin/env python3
"""Render a kotlinx-benchmark JSON report as a Markdown table for a pull request comment.

The report is JMH's own schema: one entry per benchmark-and-parameter combination, each carrying a
score, an error at 99.9% confidence, and the parameter values that produced it.

Every row prints its error alongside its score. These runs happen on shared GitHub runners whose
machine class varies between jobs, so a score without its error invites a comparison the numbers
cannot support. See "Reading these plans honestly" in docs/benchmarking.md.

Usage: benchmark_table.py <report-dir-or-file> [--marker <html-comment-marker>]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

# Lets the commenting workflow find its own previous comment and update it in place rather than
# adding another one to every push.
DEFAULT_MARKER = "<!-- micro-benchmarks -->"


def find_reports(target: pathlib.Path) -> list[pathlib.Path]:
    """The newest run's reports, not every run's.

    kotlinx-benchmark writes each run into its own timestamped directory and never prunes them, so
    a directory that has been run against more than once holds several. Merging them would print
    the same benchmark many times with different scores, which looks like flakiness rather than
    like history.
    """
    if target.is_file():
        return [target]
    reports = sorted(target.rglob("*.json"))
    if not reports:
        return []
    newest = max(report.parent for report in reports)
    return sorted(report for report in reports if report.parent == newest)


def load_entries(paths: list[pathlib.Path]) -> list[dict]:
    entries: list[dict] = []
    for path in paths:
        try:
            content = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            print(f"skipping {path}: {error}", file=sys.stderr)
            continue
        if isinstance(content, list):
            entries.extend(content)
    return entries


def short_name(fully_qualified: str) -> tuple[str, str]:
    """Splits `a.b.ClassBenchmark.method` into its class and method."""
    parts = fully_qualified.split(".")
    if len(parts) < 2:
        return "", fully_qualified
    return parts[-2], parts[-1]


def format_params(params: dict) -> str:
    if not params:
        return ""
    return ", ".join(f"`{key}={value}`" for key, value in sorted(params.items()))


def format_number(value: float) -> str:
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 10:
        return f"{value:.1f}"
    return f"{value:.3f}"


def render(entries: list[dict], marker: str) -> str:
    if not entries:
        return (
            f"{marker}\n### Micro benchmarks\n\n"
            "The run produced no benchmark entries. That usually means every benchmark failed its "
            "`@Setup` assertions rather than that none exist — check the job log."
        )

    rows = []
    for entry in entries:
        metric = entry.get("primaryMetric", {})
        class_name, method = short_name(entry.get("benchmark", "?"))
        rows.append(
            (
                class_name,
                method,
                format_params(entry.get("params", {})),
                format_number(metric.get("score", 0.0)),
                format_number(metric.get("scoreError", 0.0)),
                metric.get("scoreUnit", ""),
            )
        )
    rows.sort(key=lambda row: (row[0], row[1], row[2]))

    lines = [
        marker,
        "### Micro benchmarks",
        "",
        f"{len(rows)} benchmarks, lower is better. `±` is the 99.9% confidence interval.",
        "",
        "> Indicative only. These run on shared GitHub runners whose hardware varies between jobs,",
        "> so differences between runs are not evidence of a regression unless they are much larger",
        "> than the error column. This job exists to run the benchmarks' own assertions; the numbers",
        "> are a by-product.",
        "",
        "| Class | Benchmark | Params | Score | ± Error | Units |",
        "| --- | --- | --- | ---: | ---: | --- |",
    ]
    for class_name, method, params, score, error, unit in rows:
        lines.append(
            f"| {class_name} | `{method}` | {params} | {score} | {error} | {unit} |"
        )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("--marker", default=DEFAULT_MARKER)
    arguments = parser.parse_args()

    if not arguments.report.exists():
        print(f"no report at {arguments.report}", file=sys.stderr)
        return 1

    entries = load_entries(find_reports(arguments.report))
    print(render(entries, arguments.marker))
    return 0


if __name__ == "__main__":
    sys.exit(main())
