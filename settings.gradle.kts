rootProject.name = "RadarNebo"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download JDK toolchains it can't find locally. Compose Hot Reload (the IDE's "[hot]" run
// configuration, hotRunJvm/hotDev) asks for a JetBrains Runtime 25 toolchain, which most machines don't have.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":core")
include(":shared")
include(":server")
include(":server-console")
include(":app:android")
include(":app:desktop")
// Android Studio's hot-reload run configurations ask for the bare task "hotRun" (or "hotDev") plus --mainClass.
// Gradle runs a bare task in every project that has it, so each run would also start the other desktop app's
// hotRun. Narrow it to the project that owns the main class (the mainClass in each project's compose.desktop block).
// Android Studio still runs one Gradle build at a time, and hotRun lasts as long as its app: to run the server
// console next to app.desktop, start it with the "server-console (shell)" configuration (.run/), outside that queue.
val hotReloadMainClasses = mapOf(
    "com.gusak.lab.radarnebo.desktop.MainKt" to ":app:desktop",
    "com.gusak.lab.radarnebo.console.MainKt" to ":server-console",
)
gradle.startParameter.taskNames.let { names ->
    val mainClass = names.getOrNull(names.indexOf("--mainClass") + 1)?.takeIf { "--mainClass" in names }
    val project = hotReloadMainClasses[mainClass]
    if (project != null && names.any { it == "hotRun" || it == "hotDev" }) {
        gradle.startParameter.setTaskNames(names.map { if (it == "hotRun" || it == "hotDev") "$project:$it" else it })
    }
}
