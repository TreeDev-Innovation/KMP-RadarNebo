package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract
import com.gusak.lab.radarnebo.server.domain.alert.IotVersion
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.serializer
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * The alerts.in.ua API (https://devs.alerts.in.ua), served from the server's own data: the same paths,
 * parameters and JSON, so any alerts.in.ua client can use the server instead.
 */
internal fun Route.alertsInUaRoutes(situation: SituationService, maxAge: () -> Duration, clock: () -> Long) {
    val encoded = AtomicReference<Pair<Long, String>?>(null)

    /** Active alerts: `{"alerts": [...]}`. */
    get("/v1/alerts/active.json") {
        val board = situation.board.orWarmingUp()
        if (call.notModifiedSince(board, maxAge(), clock())) return@get
        // Encoded once per board version, however many clients ask.
        val body = encoded.get()?.takeIf { it.first == board.version }?.second
            ?: AlertsInUaContract.encode(board.alerts).also { encoded.set(board.version to it) }
        call.respondText(body, ContentType.Application.Json)
    }

    /** One character per oblast, in the documented order: `"ANNN…"` (v1) or `"RNYP…"` (v2). */
    IotVersion.entries.forEach { version ->
        val v = version.name.lowercase()

        get("/$v/iot/active_air_raid_alerts_by_oblast.json") {
            val board = situation.board.orWarmingUp()
            if (call.notModifiedSince(board, maxAge(), clock())) return@get
            call.respondJsonString(situation.airRaidStatus().byOblast(version))
        }

        /** One character per location UID, at the UID's position; a space where no location has that UID. */
        get("/$v/iot/active_air_raid_alerts.json") {
            val board = situation.board.orWarmingUp()
            if (call.notModifiedSince(board, maxAge(), clock())) return@get
            call.respondJsonString(situation.airRaidStatus().byUid(version))
        }

        /** The status of one oblast, raion, hromada or city: `"A"`, `"P"` or `"N"` (v2: `"R"`, `"Y"`, `"P"`, `"N"`). */
        get("/$v/iot/active_air_raid_alerts/{file}") {
            val uid = call.parameters["file"].orEmpty().removeJsonSuffix()
            if (uid.toIntOrNull() == null) badRequest("Location UID must be a number")
            val board = situation.board.orWarmingUp()
            if (call.notModifiedSince(board, maxAge(), clock())) return@get
            call.respondJsonString(situation.airRaidStatus().location(uid).code(version).toString())
        }
    }
}

/**
 * Alert history of a location: `{"alerts": [...]}` with every alert active at some point in the period,
 * finished ones included. alerts.in.ua documents `month_ago`; `week_ago` and `day_ago` are also accepted.
 */
internal fun Route.alertHistoryRoutes(situation: SituationService, clock: () -> Long) {
    get("/v1/regions/{uid}/alerts/{file}") {
        val uid = call.parameters["uid"].orEmpty()
        if (uid.toIntOrNull() == null) badRequest("Location UID must be a number")
        val period = HistoryPeriod.from(call.parameters["file"].orEmpty().removeJsonSuffix())
            ?: badRequest("Unknown period; use ${HistoryPeriod.entries.joinToString { it.apiValue }}")
        situation.board.orWarmingUp()
        val alerts = situation.alertHistory(uid, period.since(Instant.fromEpochMilliseconds(clock())), HISTORY_LIMIT)
        call.respondText(AlertsInUaContract.encode(alerts), ContentType.Application.Json)
    }
}

internal enum class HistoryPeriod(val apiValue: String) {
    MonthAgo("month_ago"),
    WeekAgo("week_ago"),
    DayAgo("day_ago");

    fun since(now: Instant): Instant = when (this) {
        MonthAgo -> now.toJavaInstant().atZone(ZoneOffset.UTC).minusMonths(1).toInstant().toKotlinInstant()
        WeekAgo -> now - 7.days
        DayAgo -> now - 1.days
    }

    companion object {
        fun from(value: String): HistoryPeriod? = entries.firstOrNull { it.apiValue == value }
    }
}

private const val HISTORY_LIMIT = 1_000

private fun String.removeJsonSuffix(): String = if (endsWith(".json")) removeSuffix(".json") else notFound("Not found")

/** The IoT endpoints answer with a bare JSON string. */
private suspend fun ApplicationCall.respondJsonString(value: String) =
    respondText(AlertsInUaContract.json.encodeToString(String.serializer(), value), ContentType.Application.Json)
