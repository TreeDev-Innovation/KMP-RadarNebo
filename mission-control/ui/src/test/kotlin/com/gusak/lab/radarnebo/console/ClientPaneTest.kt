package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientPaneTest {
    @Test
    fun pointsTheClientAtTheRunningPortAndKey() {
        val saved = AppSettings(serverUrl = "http://elsewhere:1234", accessKey = "old", pollSeconds = 60)
        val pointed = saved.pointedAt(9090, "secret")
        assertEquals("http://localhost:9090", pointed.serverUrl)
        assertEquals("secret", pointed.accessKey)
        assertEquals(60, pointed.pollSeconds)
    }

    @Test
    fun dropsTheKeyWhenTheServerDoesNotAskForOne() {
        assertEquals("", AppSettings(accessKey = "old").pointedAt(8080, null).accessKey)
    }
}
