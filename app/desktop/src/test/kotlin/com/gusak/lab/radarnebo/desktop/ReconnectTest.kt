package com.gusak.lab.radarnebo.desktop

import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.di.appModules
import com.gusak.lab.radarnebo.di.inMemoryStorageModule
import com.gusak.lab.radarnebo.ui.AppViewModel
import com.gusak.lab.radarnebo.ui.UiState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** A server started after the app is picked up by the 5 s reconnect timer, or at once by Reconnect. */
class ReconnectTest {
    @Volatile
    private var serverUp = false

    /** Refuses connections until [serverUp]; then serves no alerts (tracks and health are optional). */
    private val server = MockEngine { request ->
        if (!serverUp) throw IOException("Failed to connect to localhost:8080")
        when (request.url.encodedPath) {
            RadarNeboApi.ACTIVE_ALERTS_PATH -> respond("""{"alerts":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private val koin: KoinApplication = koinApplication {
        allowOverride(true)
        modules(appModules(inMemoryStorageModule(InMemoryStorage())) + module { single { RadarNeboApi(RadarNeboApi.client(server)) } })
    }

    @AfterTest
    fun close() = koin.close()

    private suspend fun AppViewModel.await(what: (UiState) -> Boolean) = withTimeout(10.seconds) { state.first(what) }

    @Test
    fun retriesAnUnreachableServerAfterFiveSeconds() = runBlocking {
        val vm = koin.koin.get<AppViewModel>()
        val failed = vm.await { it.error != null }
        val wait = assertNotNull(failed.nextRetryAt) - Clock.System.now()
        assertTrue(wait > 3.seconds && wait <= 5.seconds, "next try in about 5 s, was $wait")

        serverUp = true
        val connected = vm.await { it.lastUpdated != null }
        assertNull(connected.error)
        assertNull(connected.nextRetryAt)
    }

    @Test
    fun reconnectTriesAtOnce() = runBlocking {
        val vm = koin.koin.get<AppViewModel>()
        vm.await { it.error != null }

        serverUp = true
        val pressed = Clock.System.now()
        vm.reconnect()
        vm.await { it.lastUpdated != null }
        assertTrue(Clock.System.now() - pressed < 2.seconds, "reconnected without waiting for the timer")
    }
}
