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

/**
 * No Gradle system properties reach the iOS test runner, so the run uses fixed defaults.
 *
 * Deliberately the smoke profile and a low iteration count: the iOS harness is a smoke check that
 * the engine works on the platform, not a source of headline numbers.
 */
internal actual fun benchmarkConfigFromEnvironment(): BenchmarkConfig =
  BenchmarkConfig.of(
    profile = Profile.SMOKE,
    warmupIterations = 1,
    measuredIterations = 3,
  )
