import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
// The macOS trackpad pinch bridge (jvmMain's MacTrackpad.java) uses the JDK-internal com.apple.eawt.event package;
// apps running it need the same export at run time.
// Targets JVM 11 like the Kotlin code; through source/target rather than --release, which forbids --add-exports.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED"))
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
