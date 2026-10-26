import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import java.io.File
import java.nio.file.Files

/**
 * Starts this project's app (`run`, and Compose Hot Reload's `hotRun`/`hotDev`) through a link to the JDK's
 * `java` named [appName], so the OS shows that name for the process (Activity Monitor, `ps`, macOS permission
 * and firewall prompts) instead of "java". The JDK's launcher resolves its real path, so it still finds its
 * JDK. Windows needs special rights for symbolic links, so there the process stays "java".
 *
 * The tasks' own launcher can't be read and replaced (reading it fixes it), so the JDK is looked up the way
 * their plugins do: the JDK Gradle runs on for `run`, as Compose uses, and JetBrains Runtime 25 for Hot Reload.
 */
fun Project.launchAs(appName: String) {
    if (System.getProperty("os.name").startsWith("Windows")) return
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val current = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion)) }
    val hotReload = toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    val link = layout.buildDirectory.file("launcher/$appName")
    tasks.withType(JavaExec::class.java).matching { it.name in setOf("run", "hotRun", "hotDev") }.configureEach {
        val jdk: Provider<JavaLauncher> = if (name == "run") current else hotReload
        javaLauncher.set(jdk.zip(link) { launcher, file -> NamedLauncher(launcher.metadata, file) })
        doFirst(CreateLink(link.get().asFile, jdk.map { it.executablePath.asFile }))
    }
}

/** A JDK ([metadata]) started through [link]. */
private class NamedLauncher(private val metadata: JavaInstallationMetadata, private val link: RegularFile) : JavaLauncher {
    override fun getMetadata() = metadata
    override fun getExecutablePath() = link
}

private class CreateLink(private val link: File, private val java: Provider<File>) : Action<Task> {
    override fun execute(task: Task) {
        val path = link.toPath()
        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
        Files.createSymbolicLink(path, java.get().toPath().toRealPath())
    }
}
