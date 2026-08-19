/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.benchmark.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import dev.ohs.fhir.engine.benchmark.AndroidBenchmarkContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The driver's only screen.
 *
 * Driven by intent extras rather than by tapping buttons, unlike android-fhir's benchmark app. UI
 * Automator taps break whenever the UI moves and need the view to be on screen and settled; `am
 * start -e workload …` does not, and it lets a workload be selected without any UI at all.
 *
 * The status view exists purely so the harness can wait for real completion instead of sleeping:
 * its text goes to [STATUS_READY] once setup is done and [STATUS_DONE] when the work finishes.
 */
class BenchmarkActivity : Activity() {

  /**
   * Benchmarks run off the main thread.
   *
   * On `Dispatchers.Main` a long workload blocks the UI thread, and the system puts up an "isn't
   * responding" dialog over the app. That both contaminates the measurement and hides the status
   * view from UI Automator, so a harness selecting on it finds nothing.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var statusView: TextView

  /**
   * The run in flight, kept so a relaunch can cancel it.
   *
   * Without this, a relaunch that arrives while the previous workload is still running leaves two
   * runs racing, and whichever finishes last writes the status. A stale run completing after a
   * failed one would stamp [STATUS_DONE] over the failure, and a harness waiting on that status
   * would record a measurement for work that never happened.
   */
  private var runJob: Job? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    statusView =
      TextView(this).apply {
        id = R.id.benchmark_status
        gravity = Gravity.CENTER
        textSize = 18f
        text = STATUS_STARTING
      }
    setContentView(statusView)

    AndroidBenchmarkContext.context = applicationContext

    start(intent)
  }

  /**
   * Handles a relaunch without a fresh process.
   *
   * The activity is `singleTop`, so a second `am start` reuses this instance rather than stacking a
   * new one on top of it. Without resetting the status here, a harness could read the previous
   * run's [STATUS_DONE] and record a measurement for work that never happened. Macrobenchmark's
   * cold startup mode avoids this too, but not every caller is macrobenchmark.
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    start(intent)
  }

  private fun start(intent: Intent) {
    setStatus(STATUS_STARTING)
    val request =
      BenchmarkRequest.from(
        listOf(
            BenchmarkRequest.KEY_WORKLOAD,
            BenchmarkRequest.KEY_GROUPS,
            BenchmarkRequest.KEY_PROFILE,
            BenchmarkRequest.KEY_WARMUP,
            BenchmarkRequest.KEY_ITERATIONS,
          )
          .associateWith { intent.getStringExtra(it) },
      )
    runJob?.cancel()
    runJob = scope.launch { execute(request) }
  }

  private suspend fun execute(request: BenchmarkRequest) {
    try {
      if (request.workloadId != null) {
        // Setup is untimed, and macrobenchmark must not start its measured block until it is
        // finished — hence STATUS_READY before any measured work happens.
        val run = BenchmarkDriver.prepareSingle(request)
        setStatus(STATUS_READY)
        run.beforeEach()
        run.measureOnce()
        run.afterEach()
        setStatus(STATUS_DONE)
        Log.i(TAG, "completed ${request.workloadId}")
      } else {
        setStatus(STATUS_READY)
        val summary = BenchmarkDriver.runAll(request)
        setStatus(STATUS_DONE)
        Log.i(TAG, summary)
      }
    } catch (e: CancellationException) {
      // A newer launch replaced this run. It owns the status now; writing a failure here would
      // overwrite the live run's state with the corpse of the one it superseded.
      throw e
    } catch (e: Throwable) {
      // Surfaced in the view as well as the log: a harness waiting on STATUS_DONE would otherwise
      // just time out with nothing to say.
      Log.e(TAG, "benchmark failed", e)
      setStatus("$STATUS_FAILED ${e::class.simpleName}: ${e.message}")
    }
  }

  /** Callable from the benchmark thread; the view itself is only ever touched on the UI thread. */
  private fun setStatus(status: String) {
    runOnUiThread { statusView.text = status }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    const val TAG = "BenchmarkDriver"

    const val STATUS_STARTING = "starting"
    const val STATUS_READY = "ready"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
  }
}
