import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// client.properties at the repo root (gitignored; see client.properties.example) sets the server address and access key
// the apps start with. They're generated into ClientConfig.kt; without the file both are blank.
val clientProperties = providers.fileContents(rootProject.layout.projectDirectory.file("client.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) } }
val generateClientConfig by tasks.registering {
    val serverUrl = clientProperties.map { it.getProperty("server.url").orEmpty().trim() }.orElse("")
    val accessKey = clientProperties.map { it.getProperty("server.accessKey").orEmpty().trim() }.orElse("")
    val outputDir = layout.buildDirectory.dir("generated/clientConfig")
    inputs.property("serverUrl", serverUrl)
    inputs.property("accessKey", accessKey)
    outputs.dir(outputDir)
    doLast {
        fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = outputDir.get().file("com/gusak/lab/radarnebo/data/ClientConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.gusak.lab.radarnebo.data
            |
            |/** Generated from client.properties by :shared:generateClientConfig. Blank values mean "not set". */
            |internal object ClientConfig {
            |    const val SERVER_URL = ${literal(serverUrl.get())}
            |    const val ACCESS_KEY = ${literal(accessKey.get())}
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    android {
       namespace = "com.gusak.lab.radarnebo.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        commonMain {
            kotlin.srcDir(generateClientConfig)
        }
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            api(libs.koin.android)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
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
            api(project(":core"))
            implementation(libs.kotlinx.datetime)
            api(libs.koin.core)
            api(libs.koin.compose)
            api(libs.koin.compose.viewmodel)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
// Targets JVM 11 like the Kotlin code. The macOS trackpad pinch bridge (jvmMain's MacTrackpad.java) reaches the
// JDK-internal com.apple.eawt.event package by reflection, so it compiles on any JDK; apps running it need
// --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED at run time.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

// ./gradlew :shared:jvmTest --tests '*ContrastScreenshotsTest*' -PcontrastScreenshots[=Radar,Nord,…] renders every overlay
// and dialog per map background and theme (downloading satellite tiles) into build/contrast.
tasks.named<Test>("jvmTest") {
    providers.gradleProperty("contrastScreenshots").orNull?.let { palettes ->
        systemProperty("contrast.screenshots.dir", layout.buildDirectory.dir("contrast").get().asFile.absolutePath)
        if (palettes.isNotBlank()) systemProperty("contrast.palettes", palettes)
        // Each combination takes over a minute, longer than runTest allows by default.
        systemProperty("kotlinx.coroutines.test.default_timeout", "20m")
        outputs.upToDateWhen { false }
    }
}
