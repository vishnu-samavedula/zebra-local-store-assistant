plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

val nextTextRuntimeProbe = providers.gradleProperty("nextTextRuntime").orNull == "true"
val useNextTextRuntime = providers.gradleProperty("legacyTextRuntime").orNull != "true"

android {
    namespace = "com.example.zebralocalai"
    compileSdk = 36
    defaultConfig {
        applicationId = if (nextTextRuntimeProbe) {
            "com.example.zebralocalai.runtimeprobe"
        } else {
            "com.example.zebralocalai"
        }
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USE_NEXT_TEXT_RUNTIME", useNextTextRuntime.toString())

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    sourceSets.getByName("main").assets.directories.add("../../seed_data")
    sourceSets.getByName("main").assets.directories.add("../../contracts")

    packaging {
      jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/*.so"
      }
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Local tests
  testImplementation(libs.junit)

  // Instrumented tests
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)

}
