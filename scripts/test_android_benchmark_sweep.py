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
"""Unit tests for the pure logic in android_benchmark_sweep.

The device path cannot be tested here. Everything that decides what to run, and
what a run meant, can be.
"""

import tempfile
import unittest
from pathlib import Path

import android_benchmark_sweep as sweep


def write_catalogue(directory, **files):
    for name, body in files.items():
        (directory / f"{name}.kt").write_text(body)


class DiscoverWorkloadsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_reads_ids_from_the_catalogue_sources(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='override val id = "crud.create_batch"\noverride val id = "crud.update"',
            SearchWorkloads='search("search.patient_by_family") { env ->',
            SyncWorkloads='override val id = "sync.download_batch"',
            ServerWorkloads='override val id = "server.upload_creates"',
        )

        self.assertEqual(
            [
                "crud.create_batch",
                "crud.update",
                "search.patient_by_family",
                "sync.download_batch",
                "server.upload_creates",
            ],
            sweep.discover_workloads(self.dir),
        )

    def test_keeps_each_id_once_when_it_appears_twice(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='"crud.create_batch"\nmeasure("crud.create_batch")',
            SearchWorkloads='search("search.patient_by_family")',
            SyncWorkloads='"sync.download_batch"',
        )

        self.assertEqual(1, sweep.discover_workloads(self.dir).count("crud.create_batch"))

    def test_refuses_a_catalogue_with_no_workloads_for_a_device_group(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='override val id = "crud.create_batch"',
            SearchWorkloads="// none yet",
            SyncWorkloads='override val id = "sync.download_batch"',
        )

        with self.assertRaises(sweep.CatalogueError) as raised:
            sweep.discover_workloads(self.dir)
        self.assertIn("search", str(raised.exception))


class SelectionTest(unittest.TestCase):
    ALL = [
        "crud.create_batch",
        "search.patient_by_family",
        "sync.download_batch",
        "server.upload_creates",
    ]

    def test_device_workloads_exclude_the_server_group(self):
        self.assertEqual(
            ["crud.create_batch", "search.patient_by_family", "sync.download_batch"],
            sweep.device_workloads(self.ALL),
        )

    def test_server_workloads_are_reported_rather_than_dropped(self):
        self.assertEqual(["server.upload_creates"], sweep.undriveable_workloads(self.ALL))

    def test_a_requested_server_workload_is_rejected_before_gradle_runs(self):
        with self.assertRaises(sweep.SelectionError) as raised:
            sweep.resolve_requested(["server.upload_creates"], self.ALL)
        self.assertIn("no macrobenchmark class", str(raised.exception))

    def test_an_unknown_workload_is_rejected(self):
        with self.assertRaises(sweep.SelectionError):
            sweep.resolve_requested(["crud.nope"], self.ALL)

    def test_class_for_group(self):
        self.assertTrue(
            sweep.class_for("search.patient_by_family").endswith("FhirEngineSearchMacrobenchmark")
        )


BENCHMARK_DATA = {
    "benchmarks": [
        {
            "name": "crud",
            "className": "dev.ohs.fhir.engine.benchmark.macro.FhirEngineCrudMacrobenchmark",
            "metrics": {
                "crud.create_batchCount": {"median": 1.0, "runs": [1.0]},
                "crud.create_batchSumMs": {"median": 330.6, "runs": [330.6]},
            },
        }
    ]
}


class MetricsTest(unittest.TestCase):
    def test_reads_the_sum_for_the_requested_workload(self):
        metrics = sweep.metrics_for(BENCHMARK_DATA, "crud.create_batch")

        self.assertAlmostEqual(330.6, metrics["sum_ms_median"])
        self.assertEqual([330.6], metrics["runs"])
        self.assertEqual(1, metrics["iterations"])

    def test_ignores_metrics_belonging_to_another_workload(self):
        self.assertIsNone(sweep.metrics_for(BENCHMARK_DATA, "crud.update")["sum_ms_median"])


class ClassifyTest(unittest.TestCase):
    def test_a_clean_run_with_a_measurement_is_ok(self):
        outcome = sweep.classify(
            returncode=0, timed_out=False, logcat="", gradle_log="", sum_ms=330.6
        )
        self.assertEqual("ok", outcome.status)

    def test_a_clean_run_that_measured_nothing_is_not_ok(self):
        outcome = sweep.classify(
            returncode=0, timed_out=False, logcat="", gradle_log="", sum_ms=0.0
        )
        self.assertEqual("no-metric", outcome.status)

    def test_an_out_of_memory_kill_outranks_the_timeout_it_causes(self):
        # The app dies, the status view never settles, and the wait runs to the clock. Reporting
        # that as a timeout would hide the reason.
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate",
            gradle_log="did not finish within 600000ms",
            sum_ms=None,
        )
        self.assertEqual("oom", outcome.status)

    def test_a_low_memory_killer_kill_of_the_driver_counts_as_out_of_memory(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="lmkd: Kill 'dev.ohs.fhir.engine.benchmark.app' (1234), uid 10234",
            gradle_log="",
            sum_ms=None,
        )
        self.assertEqual("oom", outcome.status)

    def test_a_kill_of_an_unrelated_package_is_not_our_out_of_memory(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="lmkd: Kill 'com.example.other' (1234), uid 10999",
            gradle_log="",
            sum_ms=None,
        )
        self.assertEqual("timeout", outcome.status)

    def test_a_run_past_the_wall_clock_is_a_timeout(self):
        outcome = sweep.classify(
            returncode=None, timed_out=True, logcat="", gradle_log="", sum_ms=None
        )
        self.assertEqual("timeout", outcome.status)

    def test_any_other_non_zero_exit_is_a_failure_and_keeps_its_reason(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=False,
            logcat="",
            gradle_log="IllegalStateException: Workload crud.create_batch failed. Status: failed",
            sum_ms=None,
        )
        self.assertEqual("failed", outcome.status)
        self.assertIn("IllegalStateException", outcome.detail)


class CorpusNoteTest(unittest.TestCase):
    COUNTS = {"Patient": 50000, "Encounter": 41770, "Organization": 37699}

    def test_flags_a_workload_whose_resource_type_is_absent_from_the_corpus(self):
        self.assertEqual(
            "corpus has no Observation",
            sweep.corpus_note("search.observation_by_code", self.COUNTS),
        )

    def test_flags_the_absent_type_of_a_workload_that_names_two(self):
        self.assertEqual(
            "corpus has no Observation",
            sweep.corpus_note("search.patient_revinclude_observation", self.COUNTS),
        )

    def test_says_nothing_when_every_type_the_workload_names_is_present(self):
        self.assertIsNone(sweep.corpus_note("search.patient_by_family", self.COUNTS))


if __name__ == "__main__":
    unittest.main()
