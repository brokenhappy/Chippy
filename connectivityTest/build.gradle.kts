import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

@Suppress("DEPRECATION")
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ConnectivityTest"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":composeApp"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "com.woutwerkman.connectivitytest"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ============================================================================
// Connectivity Test Task
// ============================================================================

tasks.register<JavaExec>("testConnectivity") {
    group = "verification"
    description = "Test network connectivity between platforms"

    timeout.set(Duration.ofSeconds(80))

    val jvmCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    val runtimeFiles = jvmCompilation.runtimeDependencyFiles ?: files()
    classpath = runtimeFiles + jvmCompilation.output.allOutputs
    mainClass.set("com.woutwerkman.connectivitytest.ConnectivityTestRunnerKt")
    systemProperty("project.root", rootDir.absolutePath)

    val skipPlatforms = project.findProperty("skip-platforms")?.toString() ?: ""
    if (skipPlatforms.isNotEmpty()) {
        args("--skip-platform", skipPlatforms)
    }

    if (project.hasProperty("no-headless")) {
        args("--no-headless")
    }

    dependsOn(":connectivityTest:jvmMainClasses")
}
