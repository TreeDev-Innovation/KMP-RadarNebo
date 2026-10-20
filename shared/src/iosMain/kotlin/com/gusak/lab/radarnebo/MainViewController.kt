package com.gusak.lab.radarnebo

import androidx.compose.ui.window.ComposeUIViewController
import com.gusak.lab.radarnebo.data.ThemeMode
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

private val storage = UserDefaultsStorage()

fun MainViewController(): UIViewController {
    lateinit var controller: UIViewController
    controller = ComposeUIViewController {
        App(
            storage,
            // Override the interface style only for an explicit choice; "System" must release it, or
            // the system appearance could no longer be detected.
            onThemeApplied = { mode, _ ->
                controller.overrideUserInterfaceStyle = when (mode) {
                    ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
                    ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
                    ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
                }
            },
        )
    }
    return controller
}
