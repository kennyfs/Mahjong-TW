import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

fun git(vararg args: String): String? =
  runCatching { providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim() }.getOrNull()?.ifEmpty { null }

// Release signing from the environment (build-apk.sh sets it); without it the release build uses the debug key.
val keystorePath: String? = System.getenv("MAHJONG_KEYSTORE")

android {
  namespace = "io.github.kennyfs.mahjong"
  compileSdk = 37
  ndkVersion = "30.0.16248370"

  defaultConfig {
    applicationId = "io.github.kennyfs.mahjong"
    minSdk = 26
    targetSdk = 37
    // Minutes from 2026-01-01 to the last commit: rises with every commit, so a newer build installs over an older
    // one. The name is the vX.Y.Z tag of a release, or a beta after the latest tag (0.0.N before the first one).
    versionCode = (((git("log", "-1", "--format=%ct")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)) - 1767225600L) / 60).toInt()
    versionName = System.getenv("MAHJONG_VERSION")
      ?: git("describe", "--tags", "--match", "v[0-9]*", "--abbrev=0")?.let { tag ->
        val since = git("rev-list", "--count", "$tag..HEAD") ?: "0"
        tag.removePrefix("v") + if (since == "0") "" else "+beta.$since"
      } ?: "0.0.${git("rev-list", "--count", "HEAD") ?: "0"}"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    externalNativeBuild {
      cmake {
        // 16 KB pages, which Google Play requires; the C++ library linked in (no other native library needs it yet:
        // with ONNX Runtime, Thirteen's way, it would be shared).
        arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
      }
    }
  }

  signingConfigs {
    if (keystorePath != null) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("MAHJONG_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("MAHJONG_KEY_ALIAS")
        keyPassword = System.getenv("MAHJONG_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    debug {
      // x86_64 for the emulator.
      ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    release {
      // arm64 and x86_64 (the emulator) until it goes to a store: MAHJONG_ABIS=arm64-v8a for a phone's only.
      ndk { abiFilters += (System.getenv("MAHJONG_ABIS")?.split(",") ?: listOf("arm64-v8a", "x86_64")) }
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
  androidResources {
    // The app's own languages (Taiwan's Traditional Chinese now, English later): the libraries bring strings in
    // dozens more, which only make the APK larger.
    localeFilters += listOf("zh-rTW", "en")
  }
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
  implementation("androidx.activity:activity-compose:1.13.0")
  // activity-compose asks for 1.0.0; Thirteen's navigation3 brought 1.1.1, the version in the offline cache.
  implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("androidx.core:core-ktx:1.19.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  implementation(project(":protocol"))
  implementation("com.squareup.okhttp3:okhttp:5.5.1")
  implementation("com.google.android.gms:play-services-nearby:19.5.1")

  testImplementation("junit:junit:4.13.2")
  androidTestImplementation(composeBom)
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.test:core:1.7.0")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
