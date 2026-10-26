package com.gusak.lab.radarnebo.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Taskbar
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Gives a desktop app its name and icon instead of the JVM's ("MainKt" and the Java cup): the macOS app
 * menu and Dock, the Windows/Linux taskbar. Call first thing in `main()`, before any window opens. Returns the
 * icon for `Window(icon = …)`, or null when [iconResource] is missing. The Gradle builds also pass
 * `-Xdock:name`/`-Xdock:icon`, so macOS shows them from launch rather than once this runs.
 */
fun applyAppIdentity(name: String, iconResource: String): Painter? {
    System.setProperty("apple.awt.application.name", name)
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(iconResource)?.use { it.readBytes() } ?: return null
    runCatching {
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = ImageIO.read(ByteArrayInputStream(bytes))
        }
    }
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
