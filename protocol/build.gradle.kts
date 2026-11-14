// The battle protocol and rooms, shared by the server (server/) and the app (android/), which both include it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.serialization")
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
  // The room tests play with the real engine (cpp/build/mahjong); they are skipped without it.
  environment("MAHJONG_BINARY", rootDir.resolve("../cpp/build/mahjong").path)
}

// The screens' times (android/app/src/main/res/raw/timing.json), which the rooms pace their tables by (Pace.standard):
// among the protocol's resources, next to its classes.
tasks.processResources {
  from(projectDir.resolve("../android/app/src/main/res/raw/timing.json")) { into("io/github/kennyfs/mahjong/protocol") }
}
