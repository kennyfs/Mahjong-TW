import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

val ortVersion = "1.31.1"
// ONNX Runtime, for the network: its AAR, unpacked so the native engine can compile against its C++ headers and link
// its libonnxruntime.so (headers/ and jni/<abi>/ inside the AAR). MAHJONG_ORT_DIR, the same layout, is a reduced build
// instead (only the operators the network uses: Thirteen's android/build-ort.sh makes one that covers them).
val ortAar: Configuration = configurations.create("ortAar")
val ownOrt: String? = System.getenv("MAHJONG_ORT_DIR")?.ifEmpty { null }
val ortDir = ownOrt?.let { layout.projectDirectory.dir(it) } ?: layout.buildDirectory.dir("onnxruntime").get()
val extractOrt = tasks.register<Copy>("extractOrt") {
  enabled = ownOrt == null
  from({ zipTree(ortAar.singleFile) })
  into(ortDir)
}

// The network the app ships (an actor's .onnx, not in the repository): MAHJONG_MODEL, or the one the web page was
// built with. It goes into the assets as actor.onnx; its id (the start of its SHA-256) tells the app which network
// reviewed a record, and names the copy the engine loads.
val modelFile = file(System.getenv("MAHJONG_MODEL")?.ifEmpty { null } ?: "../../web/engine-files/actor.onnx")
val modelAssets = layout.buildDirectory.dir("model-assets")
val modelId: String = if (!modelFile.exists()) "none" else
  MessageDigest.getInstance("SHA-256").digest(modelFile.readBytes()).take(4).joinToString("") { b -> "%02x".format(b) }
val copyModel = tasks.register<Copy>("copyModel") {
  from(modelFile)
  into(modelAssets)
  rename { "actor.onnx" }
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
    buildConfigField("String", "MODEL_ID", "\"$modelId\"")
    externalNativeBuild {
      cmake {
        // 16 KB pages, which Google Play requires. The C++ library linked in: libonnxruntime.so has its own, and
        // only its C API crosses between the two.
        arguments += listOf("-DORT_DIR=${ortDir.asFile.absolutePath}", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
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
    buildConfig = true
  }
  androidResources {
    // The app's own languages (Taiwan's Traditional Chinese now, English later): the libraries bring strings in
    // dozens more, which only make the APK larger.
    localeFilters += listOf("zh-rTW", "en")
  }
  // The network, copied by copyModel.
  sourceSets["main"].assets.directories += modelAssets.get().asFile.absolutePath
  // The reduced library goes in as it is; the AAR's comes with the AAR.
  if (ownOrt != null) sourceSets["main"].jniLibs.directories += ortDir.dir("jni").asFile.absolutePath
  packaging {
    // The AAR and the imported library in CMake both bring it.
    jniLibs.pickFirsts += "**/libonnxruntime.so"
    // The Java API's JNI library: unused (the engine calls the C API), and not aligned to 16 KB pages.
    jniLibs.excludes += "**/libonnxruntime4j_jni.so"
  }
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.named("preBuild") {
  dependsOn(extractOrt, copyModel)
  doFirst {
    if (!modelFile.exists()) throw GradleException("The network is missing: set MAHJONG_MODEL to an actor's .onnx (android/README.md); looked for $modelFile")
  }
}

dependencies {
  add(ortAar.name, "com.microsoft.onnxruntime:onnxruntime-android:$ortVersion@aar")
  if (ownOrt == null) implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")

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
