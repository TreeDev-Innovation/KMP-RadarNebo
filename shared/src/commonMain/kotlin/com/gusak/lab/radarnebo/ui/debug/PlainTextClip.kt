package com.gusak.lab.radarnebo.ui.debug

import androidx.compose.ui.platform.ClipEntry

/** Clipboard content holding [text] as plain text; each platform builds its own kind of [ClipEntry]. */
internal expect fun plainTextClip(text: String): ClipEntry
