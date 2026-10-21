plugins {
  id("com.chaquo.python")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

val nextTextRuntimeProbe = providers.gradleProperty("nextTextRuntime").orNull == "true"
val useNextTextRuntime = providers.gradleProperty("legacyTextRuntime").orNull != "true"
val uiOnly = providers.gradleProperty("uiOnly").orNull == "true"

val harnessPython = layout.buildDirectory.dir("generated/harness-python")
val copyHarnessPython by tasks.registering(Sync::class) {
  into(harnessPython)
  from("../../warehouse_harness") { include("*.py"); into("warehouse_harness") }
  from("../../scripts") { include("validate_tool_contracts.py"); into("scripts") }
  doLast { harnessPython.get().file("scripts/__init__.py").asFile.writeText("") }
}
chaquopy {
  defaultConfig { version = "3.11" }
  sourceSets.getByName("main") { srcDir(harnessPython.get().asFile) }
}
tasks.configureEach {
  if (name.contains("Python") && name != "copyHarnessPython") dependsOn(copyHarnessPython)
}
val retrievalAssets = layout.buildDirectory.dir("generated/retrieval-assets")
val copyRetrievalAssets by tasks.registering(Sync::class) {
  into(retrievalAssets)
  from("../../sop_retrieval/generated/colbert-baseline") {
    include("manifest.json", "cards.json", "policy.json", "token_vectors.f32")
    into("colbert-index")
  }
  from("../../warehouse_harness") { include("demo_tasks.jsonl"); into("harness") }
}
tasks.configureEach { if (name.contains("merge") && name.endsWith("Assets")) dependsOn(copyRetrievalAssets) }

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
        versionCode = 2
        versionName = "0.2-ui"
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
    sourceSets.getByName("main").assets.directories.add("../../native_runtime/notices")
    sourceSets.getByName("main").assets.directories.add(retrievalAssets.get().asFile.absolutePath)
    sourceSets.getByName("main").jniLibs.setSrcDirs(
        if (uiOnly) emptyList<String>() else listOf("src/audioCpu/jniLibs")
    )

    packaging {
      jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/*.so"
        excludes += "**/libllama-text-server.so"
        excludes += "**/libllama-text-server-next.so"
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
