#!/usr/bin/env python3
"""Render kotlinx-benchmark JSON reports as Markdown for a pull request comment.

With --base, the comment shows the change from base to head. See docs/benchmarking.md.

Usage: benchmark_table.py <head-dir-or-file> [--base <dir-or-file>] [--marker <html-comment>]
           [--base-sha <sha>] [--head-sha <sha>] [--repository <owner/name>]
           [--changed-files <file>] [--machine <file>]
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import sys

# Lets the commenting workflow find and update its previous comment.
DEFAULT_MARKER = "<!-- micro-benchmarks -->"

# A smaller change is not flagged, even when it is statistically distinct.
MIN_RELATIVE_CHANGE = 0.05

# Paths compiled into, or used to build, the desktop benchmark runner. Keyed by the label shown.
BENCHMARKED_PATHS = {
    "commonMain": ("engine/src/commonMain/",),
    "desktopMain": ("engine/src/desktopMain/",),
    "desktopBenchmark": ("engine/src/desktopBenchmark/",),
    "build": ("engine/build.gradle.kts", "gradle/libs.versions.toml", "buildSrc/"),
}

TIME_UNITS = ["ns", "us", "ms", "s"]

SHA = re.compile(r"^[0-9a-f]{40}$")

Key = tuple[str, tuple[tuple[str, str], ...]]


def find_reports(target: pathlib.Path) -> list[pathlib.Path]:
    """The newest run of each benchmark configuration.

    kotlinx-benchmark writes each run to `<configuration>/<timestamp>/` and never prunes old runs.
    """
    if target.is_file():
        return [target]
    reports = sorted(target.rglob("*.json"))
    newest_by_configuration: dict[pathlib.Path, pathlib.Path] = {}
    for report in reports:
        run, configuration = report.parent, report.parent.parent
        if run > newest_by_configuration.get(configuration, pathlib.Path()):
            newest_by_configuration[configuration] = run
    newest = set(newest_by_configuration.values())
    return [report for report in reports if report.parent in newest]


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


def key_of(entry: dict) -> Key:
    params = entry.get("params", {}) or {}
    return entry.get("benchmark", "?"), tuple(sorted((k, str(v)) for k, v in params.items()))


def short_name(fully_qualified: str) -> tuple[str, str]:
    """Splits `a.b.ClassBenchmark.method` into its class and method."""
    parts = fully_qualified.split(".")
    if len(parts) < 2:
        return "", fully_qualified
    return parts[-2], parts[-1]


def constant_params(keys: list[Key]) -> dict[str, str]:
    """Parameters that take one value wherever they appear, so the rows need not repeat them."""
    values: dict[str, set[str]] = {}
    for _, params in keys:
        for name, value in params:
            values.setdefault(name, set()).add(value)
    return {name: next(iter(seen)) for name, seen in values.items() if len(seen) == 1}


def format_params(params, omit: dict[str, str]) -> str:
    return ", ".join(f"{name}={value}" for name, value in sorted(params) if name not in omit)


def decimals_for(error: float, fallback: float) -> int:
    """Decimal places that show `error` to two significant figures, or `fallback` to three."""
    reference, figures = (error, 2) if error > 0 else (abs(fallback), 3)
    if reference <= 0:
        return 0
    return max(0, figures - 1 - math.floor(math.log10(reference)))


def display_unit(unit: str, magnitude: float) -> tuple[str, float]:
    """A time unit in which `magnitude` falls in [1, 1000), and the factor to convert to it."""
    name, _, per = unit.partition("/")
    if name not in TIME_UNITS or per != "op":
        return unit, 1.0
    index, factor = TIME_UNITS.index(name), 1.0
    while index + 1 < len(TIME_UNITS) and magnitude * factor >= 1000:
        index, factor = index + 1, factor / 1000
    while index > 0 and 0 < magnitude * factor < 1:
        index, factor = index - 1, factor * 1000
    return TIME_UNITS[index].replace("us", "µs"), factor


def format_measurements(entries: list[dict], unit: str, factor: float) -> list[str]:
    """Each entry's score and error, all to the decimal places the widest error needs."""
    metrics = [entry.get("primaryMetric", {}) for entry in entries]
    scores = [metric.get("score", 0.0) * factor for metric in metrics]
    errors = [metric.get("scoreError", 0.0) * factor for metric in metrics]
    places = decimals_for(max(errors), min(scores))
    return [f"{score:,.{places}f} ± {error:,.{places}f} {unit}" for score, error in zip(scores, errors)]


def format_change(relative: float, interval: float) -> str:
    places = min(1, decimals_for(interval * 100, relative * 100))
    change = f"{relative * 100:+.{places}f}"
    if float(change) == 0:
        change = change[1:]
    return f"{change}% ± {interval * 100:.{places}f}%"


def classify(base: dict, head: dict) -> tuple[str, float, float]:
    """A verdict, the relative change of head against base, and the interval of that change.

    The interval of the difference is the two errors combined in quadrature. Both sides run the same
    iteration count, so they share a t-quantile and this is exact.
    """
    base_metric, head_metric = base["primaryMetric"], head["primaryMetric"]
    base_score, head_score = base_metric["score"], head_metric["score"]
    if base_score <= 0:
        return "unchanged", 0.0, 0.0
    relative = (head_score - base_score) / base_score
    combined_error = math.hypot(base_metric.get("scoreError", 0.0), head_metric.get("scoreError", 0.0))
    interval = combined_error / base_score
    separated = abs(head_score - base_score) > combined_error
    if separated and abs(relative) >= MIN_RELATIVE_CHANGE:
        return ("slower" if relative > 0 else "faster"), relative, interval
    if interval > MIN_RELATIVE_CHANGE:
        return "unclear", relative, interval
    return "unchanged", relative, interval


def table(rows: list[tuple[str, str, list[str]]], columns: list[str]) -> list[str]:
    """A Markdown table with a heading row per class. Each row is (class, label, cells)."""
    lines = [
        "| Benchmark | " + " | ".join(columns) + " |",
        "| --- | " + " | ".join("---:" for _ in columns) + " |",
    ]
    current_class = None
    for class_name, label, cells in rows:
        if class_name != current_class:
            lines.append(f"| **{class_name}** |" + " |" * len(columns))
            current_class = class_name
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    return lines


def collapsed(summary: str, body: list[str]) -> list[str]:
    return ["<details>", f"<summary>{summary}</summary>", "", *body, "", "</details>", ""]


def describe_commits(base_sha: str | None, head_sha: str | None, repository: str | None) -> str:
    def link(name: str, sha: str | None) -> str | None:
        if sha is None or not SHA.match(sha):
            return None
        if repository:
            return f"{name} [`{sha[:7]}`](https://github.com/{repository}/commit/{sha})"
        return f"{name} `{sha[:7]}`"

    parts = [part for part in (link("Base", base_sha), link("head", head_sha)) if part]
    return ", ".join(parts) + "." if parts else ""


def describe_changes(changed_files: list[str] | None) -> str:
    """Says how many changed files the benchmarks run. Never echoes a path, which is untrusted."""
    if changed_files is None:
        return ""
    counts = {
        label: sum(1 for path in changed_files if path.startswith(prefixes))
        for label, prefixes in BENCHMARKED_PATHS.items()
    }
    total = sum(counts.values())
    if total == 0:
        return "No benchmarked code changed; any flag is noise."
    parts = ", ".join(f"{label} {count}" for label, count in counts.items() if count)
    return f"Benchmarked files changed: {parts}."


def machine_block(machine: str | None) -> list[str]:
    if not machine:
        return []
    # The text comes from an artifact; without backticks it cannot close the code block.
    return collapsed("Machine", ["```", machine.replace("`", "").strip(), "```"])


def header(marker: str, title_suffix: str = "") -> list[str]:
    return [marker, f"### Micro benchmarks{title_suffix}", ""]


def render_single(entries: list[dict], marker: str, machine: str | None) -> str:
    if not entries:
        return "\n".join(
            header(marker)
            + [
                "The run produced no benchmark entries. This usually means every benchmark failed "
                "its `@Setup` assertions; check the job log."
            ]
        )

    entries = sorted(entries, key=key_of)
    omit = constant_params([key_of(entry) for entry in entries])
    rows = []
    for entry in entries:
        name, params = key_of(entry)
        class_name, method = short_name(name)
        label = f"`{method}` {format_params(params, omit)}".rstrip()
        metric = entry.get("primaryMetric", {})
        unit, factor = display_unit(metric.get("scoreUnit", ""), metric.get("score", 0.0))
        rows.append((class_name, label, format_measurements([entry], unit, factor)))

    lines = header(marker) + [
        f"{len(rows)} benchmarks. Lower is better; `±` is the 99.9% confidence interval. "
        "No base to compare against, so these numbers only describe this runner.",
        "",
        *table(rows, ["Score"]),
        "",
    ]
    if omit:
        lines += [f"Every run uses {format_params(omit.items(), {})}.", ""]
    lines += machine_block(machine)
    return "\n".join(lines).rstrip()


def render_diff(
    base_entries: list[dict],
    head_entries: list[dict],
    marker: str,
    commits: str,
    changes: str,
    machine: str | None,
) -> str:
    base_by_key = {key_of(entry): entry for entry in base_entries}
    head_by_key = {key_of(entry): entry for entry in head_entries}
    omit = constant_params(list(base_by_key) + list(head_by_key))

    groups: dict[str, list] = {"flagged": [], "unclear": [], "unchanged": [], "new": []}
    counts = {"slower": 0, "faster": 0, "unclear": 0, "unchanged": 0, "new": 0}
    for key, head in sorted(head_by_key.items()):
        class_name, method = short_name(key[0])
        label = f"`{method}` {format_params(key[1], omit)}".rstrip()
        base = base_by_key.get(key)
        reference = (base or head).get("primaryMetric", {})
        unit, factor = display_unit(reference.get("scoreUnit", ""), reference.get("score", 0.0))
        if base is None:
            counts["new"] += 1
            groups["new"].append((class_name, label, format_measurements([head], unit, factor)))
            continue
        verdict, relative, interval = classify(base, head)
        counts[verdict] += 1
        change = format_change(relative, interval)
        if verdict in ("slower", "faster"):
            change = ("⚠️ " if verdict == "slower" else "✅ ") + change
        cells = [*format_measurements([base, head], unit, factor), change]
        group = "flagged" if verdict in ("slower", "faster") else verdict
        groups[group].append((class_name, label, cells))
    removed = sorted(set(base_by_key) - set(head_by_key))

    summary = (
        f"**{counts['slower']} slower, {counts['faster']} faster**, "
        f"{counts['unchanged']} unchanged, {counts['unclear']} too noisy to tell"
        + (f", {counts['new']} new" if counts["new"] else "")
        + (f", {len(removed)} removed" if removed else "")
        + "."
    )
    columns = ["Base", "Head", "Change"]
    lines = header(marker, " — base vs. head") + [summary, ""]
    context = " ".join(part for part in (commits, changes) if part)
    if context:
        lines += [context, ""]

    if groups["flagged"]:
        lines += table(groups["flagged"], columns) + [""]
    else:
        lines += ["Nothing flagged.", ""]

    if groups["unchanged"]:
        lines += collapsed(
            f"{counts['unchanged']} unchanged: no change of 5% or more",
            table(groups["unchanged"], columns),
        )
    if groups["unclear"]:
        lines += collapsed(
            f"{counts['unclear']} too noisy to tell: interval wider than 5%",
            table(groups["unclear"], columns),
        )
    if groups["new"]:
        lines += collapsed(f"{counts['new']} new", table(groups["new"], ["Head"]))
    if removed:
        lines += ["Present in base but not in head:"]
        for name, params in removed:
            class_name, method = short_name(name)
            lines.append(f"- `{class_name}.{method}` {format_params(params, omit)}".rstrip())
        lines.append("")

    notes = [
        "- Base and head ran in the same job. Lower is better.",
        "- `±` is the 99.9% confidence interval.",
        f"- Flagged: the change is outside its interval and at least {MIN_RELATIVE_CHANGE:.0%}.",
        "- Each side is one run, so expect the odd false flag.",
    ]
    if omit:
        notes += [f"- Every run uses {format_params(omit.items(), {})}."]
    lines += collapsed("How to read this", notes)
    lines += machine_block(machine)
    return "\n".join(lines).rstrip()


def read_optional(path: pathlib.Path | None) -> str | None:
    if path is None:
        return None
    try:
        return path.read_text()
    except OSError as error:
        print(f"skipping {path}: {error}", file=sys.stderr)
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("head", type=pathlib.Path)
    parser.add_argument("--base", type=pathlib.Path)
    parser.add_argument("--marker", default=DEFAULT_MARKER)
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--repository", help="owner/name, to link the commits")
    parser.add_argument("--changed-files", type=pathlib.Path, help="one changed path per line")
    parser.add_argument("--machine", type=pathlib.Path, help="a description of the runner")
    arguments = parser.parse_args()

    if not arguments.head.exists():
        print(f"no report at {arguments.head}", file=sys.stderr)
        return 1

    head_entries = load_entries(find_reports(arguments.head))
    base_entries = (
        load_entries(find_reports(arguments.base))
        if arguments.base is not None and arguments.base.exists()
        else []
    )
    machine = read_optional(arguments.machine)
    changed = read_optional(arguments.changed_files)

    # With no base entries, every row would show as new.
    if base_entries and head_entries:
        print(
            render_diff(
                base_entries,
                head_entries,
                arguments.marker,
                describe_commits(arguments.base_sha, arguments.head_sha, arguments.repository),
                describe_changes(changed.splitlines() if changed is not None else None),
                machine,
            )
        )
    else:
        print(render_single(head_entries, arguments.marker, machine))
    return 0


if __name__ == "__main__":
    sys.exit(main())
