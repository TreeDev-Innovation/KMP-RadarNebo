import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

application {
    mainClass.set("com.gusak.lab.radarnebo.server.MainKt")
}

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
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.rateLimit)
    implementation(libs.ktor.server.callLogging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.client.mock)
}

// Local secrets for `./gradlew :server:run`: KEY=value lines from server/.env (git-ignored).
// Variables already set in the environment win.
tasks.named<JavaExec>("run") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
            .filter { (key, value) -> value.isNotEmpty() && System.getenv(key) == null }
            .forEach { (key, value) -> environment(key, value) }
    }
}
