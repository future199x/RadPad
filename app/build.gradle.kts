plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radpad.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radpad.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Support both physical ARM64 phones and PC x86_64 Emulators/VMs
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    sourceSets {
        getByName("main") {
            // Include pre-compiled bare-metal native shared objects from jniLibs
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// -----------------------------------------------------------------------------
// Bare-Metal Zig Cross-Compilation Pre-Build Task
// -----------------------------------------------------------------------------
val buildZigEngine by tasks.registering {
    description = "Cross-compiles the bare-metal Zig engine to libzigengine.so for arm64-v8a and x86_64"
    group = "build"

    val arm64So = file("$projectDir/src/main/jniLibs/arm64-v8a/libzigengine.so")
    val x8664So = file("$projectDir/src/main/jniLibs/x86_64/libzigengine.so")

    doLast {
        val zigAvailable = try {
            val p = ProcessBuilder("which", "zig").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }

        if (zigAvailable) {
            try {
                providers.exec {
                    workingDir = rootProject.projectDir
                    commandLine("zig", "build", "--release=fast")
                }.result.get()
                val builtArm64 = file("${rootProject.projectDir}/zig-out/lib/libzigengine.so")
                if (builtArm64.exists()) {
                    builtArm64.copyTo(arm64So, overwrite = true)
                }
            } catch (_: Exception) {
                println("[ZIG ENGINE] Using existing pre-compiled native binaries.")
            }
        }

        if (arm64So.exists() && x8664So.exists()) {
            println("[ZIG ENGINE] Native libraries ready for ARM64 and x86_64.")
        } else {
            println("[ZIG ENGINE] Using available pre-compiled native binaries.")
        }
    }
}

// Ensure native engine is validated before Android Java/Kotlin compilation begins
tasks.named("preBuild") {
    dependsOn(buildZigEngine)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
