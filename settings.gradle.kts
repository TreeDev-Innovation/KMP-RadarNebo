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