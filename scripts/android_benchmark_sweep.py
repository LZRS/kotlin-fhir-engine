#!/usr/bin/env python3
# Copyright 2026 Open Health Stack Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Run each Android macrobenchmark workload in its own invocation, then aggregate.

One workload per Gradle invocation, so a workload that exhausts the heap takes only
itself down: the whole crud group shares a single `@Test`, and in one run the first
OutOfMemoryError ends the other five before they start.

Two phases. Triage runs everything once to find out what survives; full reruns only
the survivors at measurement iterations. At a 50,000-patient corpus the first pass
is the difference between learning what breaks in an hour and learning it in a day.

    scripts/android_benchmark_sweep.py --population 50000
    scripts/android_benchmark_sweep.py --population 50000 --triage-only
    scripts/android_benchmark_sweep.py --reuse-corpus --only crud.create_batch
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parent.parent
CATALOGUE_DIR = (
    REPO_ROOT
    / "benchmarks/core/src/commonMain/kotlin/dev/ohs/fhir/engine/benchmark/workloads"
)
MACRO_PACKAGE = "dev.ohs.fhir.engine.benchmark.macro"
DRIVER_PACKAGE = "dev.ohs.fhir.engine.benchmark.app"
MACRO_TASK = ":benchmarks:macro:connectedReleaseAndroidTest"
CORPUS_MANIFEST = REPO_ROOT / "benchmarks/core/build/benchmark-data/synthea/manifest.json"
SWEEP_ROOT = REPO_ROOT / "benchmarks/macro/build/sweeps"

# `server` is driveable like the rest, but only once --server names a base URL; without one
# the engine has nowhere to sync to and every workload in the group fails identically.
SERVER_GROUP = "server"
DEVICE_GROUPS = ("crud", "search", "sync", SERVER_GROUP)
GROUP_CLASSES = {
    "crud": f"{MACRO_PACKAGE}.FhirEngineCrudMacrobenchmark",
    "search": f"{MACRO_PACKAGE}.FhirEngineSearchMacrobenchmark",
    "sync": f"{MACRO_PACKAGE}.FhirEngineSyncMacrobenchmark",
    SERVER_GROUP: f"{MACRO_PACKAGE}.FhirEngineServerMacrobenchmark",
}
GROUP_ORDER = DEVICE_GROUPS

# Ids are string literals in the catalogue. Deriving them beats a copy that goes stale
# silently; the guard in discover_workloads is what makes the regex safe to rely on.
WORKLOAD_ID_PATTERN = re.compile(r'"((?:crud|search|sync|server)\.[a-z0-9_]+)"')

# Built per patient by AugmentedDataset rather than exported by Synthea, so they are in the
# database but never in the corpus manifest. Mirrors ClinicalMix.
GENERATED_TYPES = ("Observation", "Condition")

# Types a workload id can name. Used only to say when a query had nothing to match.
KNOWN_TYPES = (
    "Patient",
    "Encounter",
    "Organization",
    "Practitioner",
    "Observation",
    "Condition",
    "Procedure",
    "Immunization",
    "AllergyIntolerance",
    "MedicationRequest",
    "CarePlan",
)

BENCHMARK_DATA_GLOB = (
    "benchmarks/macro/build/outputs/connected_android_test_additional_output/"
    "**/*-benchmarkData.json"
)

# The fingerprint carries a hyphen once AugmentedDataset appends the clinical mix, and \w would
# stop at it — recording two different datasets under one identity.
DATASET_LINE = re.compile(r"dataset=(\S+) population=(\d+) fingerprint=([\w-]+)")


class CatalogueError(Exception):
    """The workload catalogue could not be read the way this script expects."""


class SelectionError(Exception):
    """A requested workload cannot be run on a device."""


@dataclass
class Outcome:
    status: str
    detail: str = ""


# ---------------------------------------------------------------------------------------
# Catalogue
# ---------------------------------------------------------------------------------------


def group_of(workload_id):
    return workload_id.split(".", 1)[0]


def discover_workloads(directory=CATALOGUE_DIR):
    """Every workload id in the catalogue, ordered crud, search, sync, server.

    Raises when a device group comes back empty, which is the failure mode that would
    otherwise turn a renamed file into a sweep that quietly skips a third of the suite.
    """
    seen = []
    for source in sorted(Path(directory).glob("*.kt")):
        for match in WORKLOAD_ID_PATTERN.finditer(source.read_text()):
            if match.group(1) not in seen:
                seen.append(match.group(1))
    missing = [g for g in DEVICE_GROUPS if not any(group_of(i) == g for i in seen)]
    if missing:
        raise CatalogueError(
            f"No workload ids found for group(s) {', '.join(missing)} in {directory}. "
            "The catalogue moved or the id format changed; fix WORKLOAD_ID_PATTERN.",
        )
    return sorted(seen, key=lambda i: (GROUP_ORDER.index(group_of(i)), seen.index(i)))


def driveable(workload_id, server_url=None):
    group = group_of(workload_id)
    if group == SERVER_GROUP:
        return server_url is not None
    return group in DEVICE_GROUPS


def device_workloads(workload_ids, server_url=None):
    return [i for i in workload_ids if driveable(i, server_url)]


def undriveable_workloads(workload_ids, server_url=None):
    return [i for i in workload_ids if not driveable(i, server_url)]


def class_for(workload_id):
    return GROUP_CLASSES[group_of(workload_id)]


def resolve_requested(requested, known, server_url=None):
    """Checks ids before any Gradle runs, so a typo costs a second rather than a build."""
    resolved = []
    for workload_id in requested:
        if workload_id not in known:
            raise SelectionError(f"Unknown workload id: {workload_id}")
        if not driveable(workload_id, server_url):
            raise SelectionError(
                f"{workload_id} is in the {SERVER_GROUP} group, which syncs against a real "
                "FHIR server. Pass --server <base url> to run it.",
            )
        resolved.append(workload_id)
    return resolved


def local_port(server_url):
    """The port to forward with `adb reverse`, or None when the server needs no forwarding.

    A device resolves `localhost` to itself, so a URL naming the host's loopback needs the
    port forwarded or every request lands on the phone and fails to connect.
    """
    parsed = urlparse(server_url)
    if parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        return None
    return parsed.port or (443 if parsed.scheme == "https" else 80)


# ---------------------------------------------------------------------------------------
# Reading a run
# ---------------------------------------------------------------------------------------


def metrics_for(benchmark_data, workload_id):
    """The workload's own metrics. The benchmark name is the test method, not the id."""
    empty = {"sum_ms_median": None, "count_median": None, "runs": [], "iterations": 0}
    for benchmark in benchmark_data.get("benchmarks", []):
        metrics = benchmark.get("metrics", {})
        summed = metrics.get(f"{workload_id}SumMs")
        if summed is None:
            continue
        runs = summed.get("runs", [])
        return {
            "sum_ms_median": summed.get("median"),
            "count_median": metrics.get(f"{workload_id}Count", {}).get("median"),
            "runs": runs,
            "iterations": len(runs),
        }
    return empty


def _memory_kill_line(text):
    """A memory kill of either of our processes.

    The test process counts as well as the driver: a long measured section produces a Perfetto
    trace large enough that reading it back gets the instrumentation killed, and calling that a
    plain failure hides that the cause was memory.
    """
    for line in text.splitlines():
        if DRIVER_PACKAGE not in line and MACRO_PACKAGE not in line:
            continue
        if any(marker in line for marker in ("lmkd", "lowmemorykiller", "am_kill")):
            return line.strip()
    return None


def _first_interesting_line(text):
    for line in text.splitlines():
        if any(m in line for m in ("Exception", "Error:", "FAILURE:", "Status: failed")):
            return line.strip()
    return ""


def classify(returncode, timed_out, logcat, gradle_log, sum_ms):
    """What a finished run meant.

    Out of memory is checked before the timeout it causes: the driver dies, its status
    view never settles, and the wait runs to the clock. Calling that a timeout would
    report the symptom and hide the cause.
    """
    combined = f"{logcat}\n{gradle_log}"
    if "OutOfMemoryError" in combined:
        return Outcome("oom", _first_interesting_line(combined))
    killed = _memory_kill_line(combined)
    if killed:
        return Outcome("oom", killed)
    if timed_out or "did not finish within" in gradle_log:
        return Outcome("timeout", _first_interesting_line(gradle_log))
    if returncode not in (0, None):
        return Outcome("failed", _first_interesting_line(gradle_log))
    if sum_ms:
        return Outcome("ok")
    # A macrobenchmark passes whether or not it measured anything, so a zero is a result
    # about the harness, not about the engine.
    return Outcome("no-metric", "trace section measured zero")


def corpus_note(workload_id, resource_counts, generated=GENERATED_TYPES):
    """Says when a workload queried a type nothing put in the database.

    Without this an empty result set reads as a fast one. [generated] are the types the driver
    builds at run time rather than reading from the corpus, so they are present in the database
    while absent from the corpus manifest.
    """
    flat = workload_id.replace("_", "").lower()
    missing = [
        t
        for t in KNOWN_TYPES
        if t.lower() in flat and not resource_counts.get(t) and t not in generated
    ]
    return "corpus has no " + ", ".join(missing) if missing else None


def dataset_summary(logcat):
    """The driver's own record of what it loaded, which is the only proof of the dataset."""
    match = DATASET_LINE.search(logcat)
    if not match:
        return None
    return {
        "kind": match.group(1),
        "population": int(match.group(2)),
        "fingerprint": match.group(3),
    }


# ---------------------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------------------

STATUS_ORDER = ("oom", "timeout", "failed", "no-metric", "ok", "skipped")


def render_table(records):
    headers = ("workload", "phase", "status", "wall", "median ms", "iters", "note")
    rows = [headers]
    for record in records:
        median = record.get("sum_ms_median")
        rows.append(
            (
                record["workload"],
                record.get("phase", ""),
                record["status"],
                f"{record.get('wall_seconds', 0):.0f}s" if record.get("wall_seconds") else "",
                f"{median:.1f}" if isinstance(median, (int, float)) else "",
                str(record.get("iterations", "") or ""),
                record.get("note") or record.get("detail", "")[:60],
            ),
        )
    widths = [max(len(str(r[i])) for r in rows) for i in range(len(headers))]
    lines = []
    for index, row in enumerate(rows):
        lines.append("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(row)).rstrip())
        if index == 0:
            lines.append("  ".join("-" * w for w in widths))
    return "\n".join(lines)


def summarise(records):
    counts = {status: 0 for status in STATUS_ORDER}
    for record in records:
        counts[record["status"]] = counts.get(record["status"], 0) + 1
    return counts


# ---------------------------------------------------------------------------------------
# Device and Gradle
# ---------------------------------------------------------------------------------------


def adb(*args, check=True):
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=check
    ).stdout


# Properties worth carrying into a shared report: enough to place a number on a device
# without naming the physical unit.
DEVICE_PROPERTIES = (
    "ro.product.model",
    "ro.product.manufacturer",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.fingerprint",
    "dalvik.vm.heapgrowthlimit",
    "dalvik.vm.heapsize",
)


def device_record(props):
    """The device as a reader of the report sees it.

    Deliberately without the serial: it identifies the unit, not the measurement, and this ends
    up in an artifact that gets passed around.
    """
    sdk = props.get("ro.build.version.sdk")
    return {
        "model": props.get("ro.product.model"),
        "manufacturer": props.get("ro.product.manufacturer"),
        "androidVersion": props.get("ro.build.version.release"),
        "apiLevel": int(sdk) if sdk and sdk.isdigit() else None,
        "buildFingerprint": props.get("ro.build.fingerprint"),
        "heapGrowthLimit": props.get("dalvik.vm.heapgrowthlimit"),
        "heapSizeLimit": props.get("dalvik.vm.heapsize"),
    }


def engine_revision():
    """The tree the numbers came from. Without it a report cannot be tied to any code."""
    try:
        sha = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "status", "--porcelain"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, OSError):
        return None
    return {"commit": sha, "dirty": bool(dirty)}


def preflight(server_url=None):
    """One physical device, awake. An emulator measures the host, not the engine."""
    lines = [l for l in adb("devices").splitlines()[1:] if l.strip()]
    ready = [l.split()[0] for l in lines if l.split()[-1] == "device"]
    if len(ready) != 1:
        raise SystemExit(
            f"Need exactly one device in state 'device'; adb reports {len(ready)}:\n"
            + "\n".join(lines),
        )
    props = {
        name: adb("shell", "getprop", name).strip() for name in DEVICE_PROPERTIES
    }
    model = props.get("ro.product.model", "")
    if adb("shell", "getprop", "ro.kernel.qemu").strip() == "1" or model.startswith("sdk"):
        print(f"WARNING: {model} looks like an emulator. Numbers from one are host-bound.")
    port = local_port(server_url)
    if port is not None:
        # Survives the process kill between iterations; it is a property of the adb
        # connection, not of the app.
        adb("reverse", f"tcp:{port}", f"tcp:{port}")
        print(f"Forwarded tcp:{port} to the host, so the device reaches {server_url}.")
    return device_record(props) | {"server": server_url}


def gradle_command(workload_id, args, iterations, timeout_seconds):
    runner_arg = "-Pandroid.testInstrumentationRunnerArguments"
    # The in-test wait ends below the outer clock, so the test reports its own message —
    # which names the workload and its status — instead of being killed mid-sentence.
    in_test_ms = int(timeout_seconds * 1000 * 0.8)
    command = [
        str(REPO_ROOT / "gradlew"),
        MACRO_TASK,
        "-Pbenchmark.dataset=synthea",
        f"-Pbenchmark.population={args.population}",
        f"{runner_arg}.class={class_for(workload_id)}",
        f"{runner_arg}.workload={workload_id}",
        f"{runner_arg}.profile={args.profile}",
        f"{runner_arg}.iterations={iterations}",
        f"{runner_arg}.timeoutMillis={in_test_ms}",
        # AGP uninstalls the driver after each test run, which deletes the seeded database and the
        # cached corpus scan with it. Keeping it installed is what lets one workload's seeding
        # serve the next; without it every workload starts from an empty database.
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "--console=plain",
    ]
    if args.server:
        command.insert(-1, f"{runner_arg}.server={args.server}")
    if args.reuse_corpus:
        # Gradle records the population it last generated with, so asking for a different
        # one regenerates even when the files are already on disk.
        for task in (
            ":benchmarks:core:packageBenchmarkData",
            ":benchmarks:core:generateSyntheaData",
            ":benchmarks:core:downloadSynthea",
        ):
            command += ["-x", task]
    return command


def run_gradle(command, log_path, timeout_seconds):
    """Runs to the wall clock, then kills the whole process group.

    A plain terminate leaves the Gradle daemon and the instrumentation running, and the
    next workload then contends with them.
    """
    with open(log_path, "w") as log:
        process = subprocess.Popen(
            command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
        )
        started = time.monotonic()
        try:
            returncode = process.wait(timeout=timeout_seconds)
            timed_out = False
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.wait()
            returncode, timed_out = None, True
    return returncode, timed_out, time.monotonic() - started


def newest_benchmark_data():
    """androidx overwrites this file every run, so it is copied out before the next one."""
    matches = sorted(
        REPO_ROOT.glob(BENCHMARK_DATA_GLOB), key=lambda p: p.stat().st_mtime, reverse=True
    )
    return matches[0] if matches else None


def resource_counts():
    if not CORPUS_MANIFEST.is_file():
        return {}
    return json.loads(CORPUS_MANIFEST.read_text()).get("resourceCounts", {})


def run_workload(workload_id, args, phase, iterations, run_dir, counts):
    workload_dir = run_dir / phase / workload_id
    workload_dir.mkdir(parents=True, exist_ok=True)
    timeout_seconds = args.timeout * 60
    command = gradle_command(workload_id, args, iterations, timeout_seconds)

    if args.dry_run:
        print(" ".join(command))
        return {
            "workload": workload_id,
            "phase": phase,
            "status": "skipped",
            "note": "dry run",
        }

    adb("logcat", "-c", check=False)
    gradle_log = workload_dir / "gradle.log"
    returncode, timed_out, wall = run_gradle(command, gradle_log, timeout_seconds)

    logcat = adb("logcat", "-d", check=False)
    (workload_dir / "logcat.txt").write_text(logcat)
    log_text = gradle_log.read_text()

    metrics = {"sum_ms_median": None, "count_median": None, "runs": [], "iterations": 0}
    source = newest_benchmark_data()
    if source and source.stat().st_mtime >= time.time() - wall - 60:
        shutil.copy2(source, workload_dir / "benchmarkData.json")
        metrics = metrics_for(json.loads(source.read_text()), workload_id)

    outcome = classify(returncode, timed_out, logcat, log_text, metrics["sum_ms_median"])
    return {
        "workload": workload_id,
        "phase": phase,
        "status": outcome.status,
        "detail": outcome.detail,
        "wall_seconds": wall,
        "sum_ms_median": metrics["sum_ms_median"],
        "count_median": metrics["count_median"],
        "iterations": metrics["iterations"],
        "runs": metrics["runs"],
        "dataset": dataset_summary(logcat),
        "note": corpus_note(workload_id, counts),
        "artifacts": str(workload_dir.relative_to(REPO_ROOT)),
    }


def build_once(args):
    """Builds and stages before anything is timed, so a long build is not a slow workload."""
    command = [
        str(REPO_ROOT / "gradlew"),
        ":benchmarks:app:assembleRelease",
        # The macro module is a com.android.test project, so its own release output is the
        # test APK; there is no separate assembleReleaseAndroidTest to build.
        ":benchmarks:macro:assembleRelease",
        "-Pbenchmark.dataset=synthea",
        f"-Pbenchmark.population={args.population}",
        "--console=plain",
    ]
    if args.reuse_corpus:
        for task in (
            ":benchmarks:core:packageBenchmarkData",
            ":benchmarks:core:generateSyntheaData",
            ":benchmarks:core:downloadSynthea",
        ):
            command += ["-x", task]
    print("Building and staging the corpus (untimed) ...")
    if args.dry_run:
        print(" ".join(command))
        return
    subprocess.run(command, check=True)


def write_summary(run_dir, records, device, args):
    summary = {
        "startedAt": run_dir.name,
        "device": device,
        "population": args.population,
        "profile": args.profile,
        "timeoutMinutes": args.timeout,
        "server": args.server,
        "engine": engine_revision(),
        "corpus": resource_counts(),
        "counts": summarise(records),
        "results": records,
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    table = render_table(records)
    (run_dir / "summary.txt").write_text(table + "\n")
    return table


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--population", type=int, default=50000, help="Synthea patients")
    parser.add_argument("--profile", default="standard", help="smoke, standard or large")
    parser.add_argument(
        "--timeout", type=float, default=30, help="wall-clock minutes per workload"
    )
    parser.add_argument("--triage-iterations", type=int, default=1)
    parser.add_argument("--full-iterations", type=int, default=5)
    parser.add_argument(
        "--triage-only", action="store_true", help="stop after the survivability pass"
    )
    parser.add_argument(
        "--only", nargs="+", metavar="ID", help="run these workload ids rather than all"
    )
    parser.add_argument(
        "--groups", nargs="+", choices=DEVICE_GROUPS, help="restrict to these groups"
    )
    parser.add_argument(
        "--reuse-corpus",
        action="store_true",
        help="skip Synthea generation and packaging, using what is already on disk",
    )
    parser.add_argument(
        "--server",
        metavar="URL",
        help="base URL of a FHIR server, which the server group needs; a localhost URL is "
        "forwarded to the device with adb reverse",
    )
    parser.add_argument("--dry-run", action="store_true", help="print commands only")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    known = discover_workloads()
    if args.only:
        selected = resolve_requested(args.only, known, args.server)
    else:
        selected = device_workloads(known, args.server)
        if args.groups:
            selected = [i for i in selected if group_of(i) in args.groups]

    device = {} if args.dry_run else preflight(args.server)
    run_dir = SWEEP_ROOT / datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    run_dir.mkdir(parents=True, exist_ok=True)
    print(f"Sweep {run_dir.relative_to(REPO_ROOT)} — {len(selected)} workloads on {device.get('model', '?')}")

    build_once(args)
    counts = resource_counts()

    records = []
    # Listed rather than omitted: a group that disappears from a report looks like a group
    # that passed.
    for workload_id in undriveable_workloads(known, args.server):
        records.append(
            {
                "workload": workload_id,
                "phase": "-",
                "status": "skipped",
                "note": "needs a FHIR server; pass --server",
            },
        )

    for index, workload_id in enumerate(selected, start=1):
        print(f"[triage {index}/{len(selected)}] {workload_id}")
        record = run_workload(
            workload_id, args, "triage", args.triage_iterations, run_dir, counts
        )
        print(f"    {record['status']} {record.get('detail', '')}".rstrip())
        records.append(record)
        write_summary(run_dir, records, device, args)

    survivors = [
        r["workload"] for r in records if r["phase"] == "triage" and r["status"] == "ok"
    ]
    if not args.triage_only and survivors:
        for index, workload_id in enumerate(survivors, start=1):
            print(f"[full {index}/{len(survivors)}] {workload_id}")
            record = run_workload(
                workload_id, args, "full", args.full_iterations, run_dir, counts
            )
            print(f"    {record['status']} {record.get('detail', '')}".rstrip())
            records.append(record)
            write_summary(run_dir, records, device, args)

    table = write_summary(run_dir, records, device, args)
    print()
    print(table)
    print()
    print(json.dumps(summarise(records)))
    print(f"Artifacts and summary.json under {run_dir.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (CatalogueError, SelectionError) as error:
        # A bad id or a moved catalogue is a mistake in the invocation, not a crash.
        sys.exit(f"{type(error).__name__}: {error}")
    except KeyboardInterrupt:
        # summary.json is rewritten after every workload, so an interrupted sweep keeps
        # everything it had already measured.
        sys.exit("Interrupted. Results so far are in the sweep directory.")
