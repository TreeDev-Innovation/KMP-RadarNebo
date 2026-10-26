import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// A desktop window that runs the server in-process: start/stop, its traffic, clients and logs.

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":server"))
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.logback.classic)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
}

// The name and icon macOS shows in the Dock and app switcher; -Xdock is macOS-only.
val dockArgs = if (System.getProperty("os.name").startsWith("Mac")) {
    listOf("-Xdock:name=RadarNebo Server", "-Xdock:icon=${file("icons/radarnebo-server.png").absolutePath}")
} else {
    emptyList()
}

compose.desktop {
    application {
        mainClass = "com.gusak.lab.radarnebo.console.MainKt"
        jvmArgs(*dockArgs.toTypedArray())

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "RadarNebo Server"
            packageVersion = "1.0.0"
            // Regenerate with tools/make-icons.sh.
            macOS {
                iconFile.set(project.file("icons/radarnebo-server.icns"))
                bundleID = "com.gusak.lab.radarnebo.server"
            }
            windows { iconFile.set(project.file("icons/radarnebo-server.ico")) }
            linux { iconFile.set(project.file("icons/radarnebo-server.png")) }
        }
    }
}

// Run like :server:run, also under Compose Hot Reload: with server/.env loaded and the archive under server/data.
// The console reads both from this property (see consoleConfig) rather than from environment variables and the
// working directory, because hotRunAsync, which Android Studio's runs use, starts the app from hotRun's argfile
// and only carries over its JVM arguments.
tasks.withType<JavaExec>().matching { it.name in setOf("run", "hotRun", "hotDev") }.configureEach {
    val serverDir = rootProject.file("server")
    workingDir = serverDir
    jvmArgs("-Dradarnebo.server.dir=${serverDir.absolutePath}")
}

// The process is called "RadarNebo Server" rather than "java" (buildSrc/src/main/kotlin/LaunchAs.kt).
launchAs("RadarNebo Server")
