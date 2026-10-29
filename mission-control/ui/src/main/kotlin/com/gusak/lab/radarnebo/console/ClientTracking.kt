package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.debug.HttpExchange

/** A client seen since the console started, from the requests it made. */
data class ClientInfo(
    val address: String,
    val requests: Int,
    val errors: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastRequest: String,
    val lastStatus: Int?,
    val userAgent: String?,
    val usedKey: Boolean,
)

// A `token` query parameter, not one that merely ends in "token" (`?mytoken=`).
private val TOKEN_PARAM = Regex("[?&]token=")

/** What the console knows about the client at [address] once it has made [exchange]; null before its first request. */
internal fun ClientInfo?.after(exchange: HttpExchange, address: String): ClientInfo {
    // No status means the request failed before a response went out.
    val failed = exchange.status.let { it == null || it >= 400 }
    val usedKey = exchange.requestHeaders.any { it.first.equals("Authorization", ignoreCase = true) } ||
        TOKEN_PARAM.containsMatchIn(exchange.url)
    return ClientInfo(
        address = address,
        requests = (this?.requests ?: 0) + 1,
        errors = (this?.errors ?: 0) + if (failed) 1 else 0,
        firstSeen = this?.firstSeen ?: exchange.startedAt,
        lastSeen = exchange.startedAt,
        lastRequest = "${exchange.method} ${exchange.path}",
        lastStatus = exchange.status,
        userAgent = exchange.userAgent ?: this?.userAgent,
        usedKey = usedKey || this?.usedKey == true,
    )
}
