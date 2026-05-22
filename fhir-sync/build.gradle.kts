plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
}

kotlin {
  jvmToolchain(21)

  androidLibrary {
    namespace = "com.google.android.fhir.sync"
    compileSdk = Sdk.COMPILE_SDK
    minSdk = Sdk.MIN_SDK
  }

  jvm("desktop")

  iosX64()
  iosArm64()
  iosSimulatorArm64()

  targets.configureEach {
    compilations.configureEach {
      compilerOptions.configure {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll(
          "kotlin.time.ExperimentalTime",
          "kotlinx.cinterop.ExperimentalForeignApi",
        )
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        api(project(":engine-kmp"))
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    val androidMain by getting {
      dependencies {
        implementation(libs.androidx.work.runtime)
        implementation(libs.androidx.lifecycle.livedata)
      }
    }
  }
}
