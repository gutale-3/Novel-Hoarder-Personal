plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  ndkVersion = "28.0.12433566"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.novelhoarder.tmtlrd"
    minSdk = 24
    targetSdk = 36
    // CI passes -PversionCode=<run number> so each build is newer than the last (in-app updater).
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = "1.0." + ((project.findProperty("versionCode") as String?) ?: "0")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    manifestPlaceholders["appLabel"] = "@string/app_name"

    ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      // CI restores the persistent key from a secret into SIGNING_KEYSTORE_PATH; one key across
      // builds is what lets a new APK install over the old one as an update.
      storeFile = file(System.getenv("SIGNING_KEYSTORE_PATH") ?: "${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  // Opt-in (-PabiSplit): an arm64-only APK, a third the size of the universal one.
  splits {
    abi {
      isEnable = project.hasProperty("abiSplit")
      reset()
      include("arm64-v8a")
      isUniversalApk = false
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      isDebuggable = false
      // Opt-in test build that installs next to the normal app instead of replacing it:
      //   gradle assembleDebug -PsideBySide
      if (project.hasProperty("sideBySide")) {
        applicationIdSuffix = ".test"
        // Same name as the normal build: this is the main app now, it just keeps its own ID so it
        // updates in place without losing the library.
        manifestPlaceholders["appLabel"] = "@string/app_name"
      }
      // Opt-in (-Pshrink): R8 removes unused library code (the APK's dex shrinks several-fold).
      if (project.hasProperty("shrink")) {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    jniLibs {
      // The small build (-Pshrink) stores native libraries compressed: a much smaller download,
      // unpacked once at install time.
      useLegacyPackaging = project.hasProperty("shrink")
      pickFirsts.add("**/libonnxruntime.so")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.mediapipe.tasks.genai)
  implementation("androidx.media:media:1.7.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
  implementation(libs.jsoup)
  implementation(libs.sherpa.onnx)
  implementation(libs.commons.compress)
  // implementation(libs.androidx.graphics.path) - removed unused dependency to eliminate non-16KB-aligned libandroidx.graphics.path.so
  implementation(libs.mlkit.translate)
}
