package com.gusak.lab.radarnebo.server.golden

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Characterization ("golden") tests pin today's output as a SHA-256 of a canonical text rendering, so a
 * refactor that changes behaviour fails loudly. When a change is intended, the failure names the new digest
 * and writes the canonical text under `build/golden-actual/`, to diff against the text of the old code.
 */
internal object Golden {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun assertDigest(name: String, expected: String, canonical: String) = assertDigests(listOf(Triple(name, expected, canonical)))

    /** Checks every `(name, expected digest, canonical text)` before failing, so one run reports all changes. */
    fun assertDigests(goldens: List<Triple<String, String, String>>) {
        val changed = goldens.mapNotNull { (name, expected, canonical) ->
            val actual = sha256(canonical)
            if (actual == expected) return@mapNotNull null
            val dump = File("build/golden-actual/$name.txt").apply { parentFile.mkdirs(); writeText(canonical) }
            "'$name': expected $expected, got $actual (${canonical.lines().size} lines, written to ${dump.absolutePath})"
        }
        if (changed.isNotEmpty()) fail("Golden output changed:\n" + changed.joinToString("\n"))
    }

    /** Like `assertEquals`, but also writes [actual] under `build/golden-actual/` when it differs. */
    fun assertText(name: String, expected: String, actual: String) {
        if (actual == expected) return
        val dump = File("build/golden-actual/$name.txt").apply { parentFile.mkdirs(); writeText(actual) }
        assertEquals(expected, actual, "Golden '$name' changed; the new text is in ${dump.absolutePath}")
    }

    /** Coordinates to five decimals (about a metre), so last-bit floating-point differences don't matter. */
    fun coord(value: Double): String = String.format(Locale.ROOT, "%.5f", value)
}
