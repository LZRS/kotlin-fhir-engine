import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.application)
}

// The benchmark driver. Deliberately not a Compose app like `engine-app`: its whole job is to take
// a workload id, run it, and say when it is done. The only UI is a status view that UI Automator
// can wait on, so pulling in Compose Multiplatform would add build weight and startup cost to
// something that is being measured.
android {
  namespace = "dev.ohs.fhir.engine.benchmark.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.ohs.fhir.engine.benchmark.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Macrobenchmark measures the release build, and `engine-app` declares no release signing,
      // so without this the APK cannot be installed on a device.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)

  androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

  jvm("desktop")

  js {
    browser()
    binaries.executable()
    useEsModules()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
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
    commonMain.dependencies {
      implementation(project(":benchmarks:core"))
      implementation(libs.kotlinx.coroutines.core)
    }
    androidMain.dependencies { implementation(libs.kotlinx.coroutines.android) }
    webMain.dependencies { implementation(libs.kotlinx.browser) }
  }
}

/**
 * Copies benchmark reports off the device.
 *
 * The app writes to its external files directory because that is readable by `adb pull` without
 * root, unlike internal storage. Macrobenchmark writes its own JSON elsewhere; both are kept, since
 * they measure different things — this one is the in-process report, that one is trace-derived.
 */
val pullBenchmarkReports by
  tasks.registering {
    group = "verification"
    description = "adb pull the benchmark reports written by the driver app."

    val outputDir = layout.buildDirectory.dir("reports/benchmarks/android")
    val adb =
      providers
        .environmentVariable("ANDROID_HOME")
        .orElse(
          providers.gradleProperty("sdk.dir"),
        )
        .map { "$it/platform-tools/adb" }
        .orElse("adb")
    val packageName = android.defaultConfig.applicationId

    outputs.dir(outputDir)
    doLast {
      val destination = outputDir.get().asFile
      destination.mkdirs()
      val result =
        providers
          .exec {
            commandLine(
              adb.get(),
              "pull",
              "/sdcard/Android/data/$packageName/files/.",
              destination.absolutePath,
            )
            isIgnoreExitValue = true
          }
          .standardOutput
          .asText
          .get()
      logger.lifecycle(result.trim())
      val pulled = destination.listFiles()?.filter { it.extension == "json" }.orEmpty()
      if (pulled.isEmpty()) {
        logger.warn(
          "No benchmark reports found on the device. Run the driver app with -e groups <group> " +
            "first; a single-workload run measures through a trace and writes no report.",
        )
      } else {
        logger.lifecycle("Pulled ${pulled.size} report(s) to ${destination.absolutePath}")
      }
    }
  }
