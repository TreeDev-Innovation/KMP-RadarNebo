package com.gusak.lab.radarnebo.ui.debug

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClip(text: String) = ClipEntry(ClipData.newPlainText("text", text))
