import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

@Suppress("DEPRECATION")
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":composeApp"))
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
        }

        androidMain.dependencies {
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

tasks.register<JavaExec>("testConnectibility") {
    group = "verification"
    description = "Test network connectivity between platforms"

    timeout.set(Duration.ofSeconds(60))

    val jvmCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    val runtimeFiles = jvmCompilation.runtimeDependencyFiles ?: files()
    classpath = runtimeFiles + jvmCompilation.output.allOutputs
    mainClass.set("com.woutwerkman.connectivitytest.ConnectivityTestRunnerKt")
    systemProperty("project.root", rootDir.absolutePath)

    val platformsArg = project.findProperty("platforms")?.toString() ?: ""
    if (platformsArg.isNotEmpty()) {
        args(platformsArg)
    }

    dependsOn(":connectivityTest:jvmMainClasses")
}
