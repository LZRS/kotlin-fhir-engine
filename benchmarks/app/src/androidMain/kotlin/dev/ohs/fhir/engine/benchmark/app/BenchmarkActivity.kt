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
 * The driver's only screen, launched by intent extras rather than UI taps, which break whenever the
 * UI moves. The status view lets a harness wait on real completion instead of sleeping.
 */
class BenchmarkActivity : Activity() {

  /**
   * Off the main thread: a long workload there triggers an ANR dialog, which contaminates the
   * measurement and hides the status view from UI Automator.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var statusView: TextView

  /**
   * Kept so a relaunch can cancel it. Otherwise two runs race and whichever finishes last writes
   * the status, letting a stale [STATUS_DONE] overwrite a newer failure.
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
   * The activity is `singleTop`, so a relaunch reuses this instance. Resetting the status here
   * stops a harness reading the previous run's [STATUS_DONE].
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
        // STATUS_READY marks the end of untimed setup, before any measured work.
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
      // A newer launch owns the status now; a failure here would overwrite it.
      throw e
    } catch (e: Throwable) {
      // In the view as well as the log, or a harness waiting on STATUS_DONE just times out.
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
