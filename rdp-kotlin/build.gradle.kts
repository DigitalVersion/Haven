plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Keep version in sync with [versions.jna] in gradle/libs.versions.toml
    compileOnly("net.java.dev.jna:jna:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

// Generated Kotlin sources live under kotlin/ (see tools/build-android.sh)
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build IronRDP native library from Rust source.
// Prerequisites: rustup + the three Android targets below, and either
// cargo-ndk (x86_64 build hosts) or a native clang+lld (aarch64 build hosts
// — see tools/build-rust-android-libs.sh for why aarch64 hosts can't just
// use cargo-ndk: NDK's own clang is x86_64-only, and qemu-user can't run
// rustc's multi-threaded startup reliably).
//
// The .so files under jniLibs/ ARE committed (this comment used to claim they
// were not). That only stays harmless while every shipped ABI is rebuilt from
// source here: armeabi-v7a was missing from the target list, so a Rust change
// rebuilt arm64 and x86_64 and left armv7 users on whatever binary happened to
// be checked in — the silent-staleness failure #469 was, in a different
// module. All three are built now, so the committed copies are only a cache.
val buildRdpNative by tasks.registering(Exec::class) {
    val rustDir = file("rust")
    val jniDir = file("jniLibs")
    val buildScript = file("tools/build-rust-android-libs.sh")

    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    inputs.file(buildScript)
    outputs.dir(jniDir)

    workingDir = rustDir

    // Detect NDK from ANDROID_NDK_HOME or ANDROID_SDK_ROOT
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }

    commandLine(buildScript.absolutePath)
}

// No publishing block needed — consumed via includeBuild() in settings.gradle.kts
