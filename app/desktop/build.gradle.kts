import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The macOS trackpad pinch bridge (MacTrackpad.java) uses the JDK-internal com.apple.eawt.event package.
val macGestureExports = listOf("--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(macGestureExports)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.lifecycle.viewmodelCompose)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.koin.test)
}

tasks.test {
    jvmArgs(macGestureExports.joinToString("="))
    // Where the headless snapshot test writes its PNGs.
    systemProperty("snapshot.dir", layout.buildDirectory.dir("snapshots").get().asFile.absolutePath)
    // Previews are found from their source, since @Preview isn't visible to reflection.
    systemProperty("preview.sources", rootProject.file("shared/src/commonMain/kotlin").absolutePath)
    // ./gradlew :app:desktop:test -PupdateReadmeScreenshots regenerates the README's screenshots.
    if (providers.gradleProperty("updateReadmeScreenshots").isPresent) {
        systemProperty("readme.screenshots.dir", rootProject.file("docs/screenshots").absolutePath)
        outputs.upToDateWhen { false }
    }
}

compose.desktop {
    application {
        mainClass = "com.gusak.lab.radarnebo.desktop.MainKt"
        jvmArgs("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        System.getProperty("radarnebo.settings")?.let { jvmArgs("-Dradarnebo.settings=$it") }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "RadarNebo"
            packageVersion = "1.0.0"
        }
    }
}
