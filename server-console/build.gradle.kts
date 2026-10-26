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

// Run like :server:run: from the server's directory (so both use the same archive under server/data)
// with server/.env loaded; variables already set in the environment win.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val serverDir = rootProject.file("server")
    workingDir = serverDir
    val envFile = serverDir.resolve(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
            .filter { (key, value) -> value.isNotEmpty() && System.getenv(key) == null }
            .forEach { (key, value) -> environment(key, value) }
    }
}

// The process is called "RadarNebo Server" rather than "java" (buildSrc/src/main/kotlin/LaunchAs.kt).
launchAs("RadarNebo Server")
