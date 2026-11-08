// The battle server: rooms (../protocol) over WebSockets, with `mahjong gui` processes as the engine. See
// docs/battle.md; the Docker image is built by server/Dockerfile.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
  application
}

val ktorVersion = "3.0.3"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
  mainClass.set("io.github.kennyfs.mahjong.server.MainKt")
}

dependencies {
  implementation(project(":protocol"))
  implementation("io.ktor:ktor-server-core:$ktorVersion")
  implementation("io.ktor:ktor-server-cio:$ktorVersion")
  implementation("io.ktor:ktor-server-websockets:$ktorVersion")
  implementation("org.slf4j:slf4j-simple:2.0.16")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
  testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
  testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
}

tasks.test {
  // The game test needs the C++ binary; it is skipped without it.
  environment("MAHJONG_BINARY", rootDir.resolve("../cpp/build/mahjong").path)
}
