package com.gusak.lab.radarnebo.ui.debug

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String) = ClipEntry.withPlainText(text)
