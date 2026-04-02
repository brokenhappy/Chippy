import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("jvm")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        // Share web server code between JVM desktop and Android (both run on JVM)
        val jvmSharedDir = "src/jvmSharedMain/kotlin"
        jvmMain.get().kotlin.srcDir(jvmSharedDir)
        androidMain.get().kotlin.srcDir(jvmSharedDir)

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.common)
            // Only add platform-specific compose desktop for actual desktop builds
            val osName = System.getProperty("os.name")
            val targetOs = when {
                osName == "Mac OS X" -> "macos"
                osName.startsWith("Win") -> "windows"
                osName.startsWith("Linux") -> "linux"
                else -> null
            }
            val targetArch = when (System.getProperty("os.arch")) {
                "x86_64", "amd64" -> "x64"
                "aarch64" -> "arm64"
                else -> null
            }
            if (targetOs != null && targetArch != null) {
                implementation("org.jetbrains.compose.desktop:desktop-jvm-$targetOs-$targetArch:${libs.versions.composeMultiplatform.get()}")
            }
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.network.tls.certificates)
            implementation(libs.qrcode.kotlin)
            implementation(libs.jmdns)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.network.tls.certificates)
            implementation(libs.qrcode.kotlin)
            implementation(libs.jmdns)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.woutwerkman"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.woutwerkman"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

// Copy wasmJs webpack output so host servers can serve the Compose web client.
// Includes a manifest listing all files so the server can pre-load them.
val copyWasmWebClient by tasks.registering(Sync::class) {
    from(layout.buildDirectory.dir("kotlin-webpack/wasmJs/developmentExecutable"))
    from(layout.buildDirectory.dir("processedResources/wasmJs/main")) {
        include("index.html")
    }
    into(layout.buildDirectory.dir("generated/wasmWebClient"))
    dependsOn("wasmJsBrowserDevelopmentWebpack", "wasmJsProcessResources")
    doLast {
        val dir = destinationDir
        val names = dir.listFiles()
            ?.filter { it.isFile && it.name != "manifest.txt" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        File(dir, "manifest.txt").writeText(names.joinToString("\n"))
    }
}

// JVM/Android: wire as classpath resources
tasks.named("jvmProcessResources") { dependsOn(copyWasmWebClient) }
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(
    copyWasmWebClient.map { it.destinationDir }
)

// iOS: copy WASM web client into the app bundle during the Xcode build.
// Registered as a standalone task to avoid capturing script references
// in embedAndSignAppleFrameworkForXcode (which would break configuration cache).
tasks.register("copyWasmToAppBundle") {
    dependsOn("copyWasmWebClient")
    val srcDir = layout.buildDirectory.dir("generated/wasmWebClient")
    val targetBuildDir = providers.environmentVariable("TARGET_BUILD_DIR")
    val fullProductName = providers.environmentVariable("FULL_PRODUCT_NAME")
    inputs.dir(srcDir)
    onlyIf { targetBuildDir.isPresent && fullProductName.isPresent }
    doLast {
        val src = srcDir.get().asFile
        val dst = File("${targetBuildDir.get()}/${fullProductName.get()}/wasmWebClient")
        if (src.exists()) {
            src.copyRecursively(dst, overwrite = true)
        }
    }
}
tasks.configureEach {
    if (name == "embedAndSignAppleFrameworkForXcode") {
        dependsOn("copyWasmToAppBundle")
    }
}

compose.desktop {
    application {
        mainClass = "com.woutwerkman.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.woutwerkman"
            packageVersion = "1.0.0"
        }
    }
}
