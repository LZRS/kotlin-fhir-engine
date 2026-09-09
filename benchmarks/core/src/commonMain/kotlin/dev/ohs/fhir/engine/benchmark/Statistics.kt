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
package dev.ohs.fhir.engine.benchmark

import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** Summary of a workload's measured samples, in milliseconds. */
@Serializable
data class Statistics(
  val min: Double,
  val median: Double,
  /**
   * Null when fewer than [MIN_SAMPLES_FOR_P90] samples were measured, which includes the default
   * run. Reported as absent rather than as a number, because the number would not mean what its
   * name promises.
   */
  val p90: Double?,
  val max: Double,
  val mean: Double,
  /** Sample standard deviation. Zero for a single sample, where spread is undefined. */
  val stdDev: Double,
) {
  companion object {
    /**
     * Below ten samples nothing falls in the top decile at all, so an interpolated 90th percentile
     * is a relabelled maximum: at five samples it lands between the fourth and the largest however
     * the run went. Raise `-Pbenchmark.iterations` to at least this to get a real tail figure.
     */
    const val MIN_SAMPLES_FOR_P90 = 10

    fun of(samplesMillis: List<Double>): Statistics {
      require(samplesMillis.isNotEmpty()) { "Cannot summarise an empty sample list." }
      val sorted = samplesMillis.sorted()
      val mean = sorted.sum() / sorted.size
      return Statistics(
        min = sorted.first(),
        median = sorted.percentile(0.50),
        p90 = if (sorted.size >= MIN_SAMPLES_FOR_P90) sorted.percentile(0.90) else null,
        max = sorted.last(),
        mean = mean,
        stdDev = sorted.sampleStandardDeviation(mean),
      )
    }

    /**
     * Bessel's correction. These samples estimate how much a workload's timing varies; they are not
     * the entire population of its timings, so the sum of squares is divided by n-1. Dividing by n
     * would report a run as more consistent than the evidence supports.
     */
    private fun List<Double>.sampleStandardDeviation(mean: Double): Double {
      if (size < 2) return 0.0
      return sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
    }

    /** Linear interpolation between the two nearest ranks. */
    private fun List<Double>.percentile(fraction: Double): Double {
      if (size == 1) return first()
      val rank = fraction * (size - 1)
      val lower = rank.toInt()
      val upper = minOf(lower + 1, size - 1)
      return this[lower] + (this[upper] - this[lower]) * (rank - lower)
    }
  }
}
