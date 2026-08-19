import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(21)

  // Mirrors :engine's target set: a benchmark that skips a target cannot answer whether that target
  // got slower.
  androidLibrary {
    namespace = "dev.ohs.fhir.engine.benchmark.core"
    compileSdk = 36
    minSdk = 26
    withHostTestBuilder {}
    withDeviceTestBuilder { sourceSetTreeName = "test" }
      .configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
  }

  jvm("desktop")

  // iosX64 omitted for the same reason as :engine — Room 3 publishes no iosX64 artifacts.
  iosArm64()
  iosSimulatorArm64()

  js {
    browser()
    useEsModules()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    useEsModules()
  }

  targets.configureEach {
    compilations.configureEach {
      compilerOptions.configure {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        api(project(":engine"))
        implementation(libs.fhir.model.r4)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
    androidMain.dependencies {
      // Emits the trace sections macrobenchmark's TraceSectionMetric reads.
      implementation(libs.androidx.tracing)
    }
    webMain.dependencies {
      // For navigator.userAgent in the report's platform descriptor.
      implementation(libs.kotlinx.browser)
    }
  }
}

// Forward -P controls to the desktop harness. Registered as inputs so changing a profile re-runs
// rather than serving the previous profile's result.
tasks.named<Test>("desktopTest") {
  listOf(
      "benchmark.profile",
      "benchmark.dataset",
      "benchmark.seed",
      "benchmark.warmup",
      "benchmark.iterations",
      "benchmark.groups",
      "benchmark.report.dir",
      "benchmark.storage.dir",
    )
    .forEach { key ->
      val value = project.findProperty(key)?.toString()
      if (value != null) {
        systemProperty(key, value)
        inputs.property(key, value)
      }
    }
  // A benchmark run is never up to date; the point is to measure again.
  outputs.upToDateWhen { false }
  testLogging { showStandardStreams = true }
}
