package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.debug.TrafficMasking
import com.gusak.lab.radarnebo.debug.TrafficRecorder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.util.AttributeKey

internal class TrafficCaptureConfig {
    var recorder: TrafficRecorder = TrafficRecorder { }
}

private val StartedAt = AttributeKey<Long>("traffic.startedAt")
private val Body = AttributeKey<String>("traffic.body")

/** Runs once a response body is final but before Compression encodes it. */
private object BeforeContentEncoding : Hook<suspend (ApplicationCall, Any) -> Unit> {
    private val phase = PipelinePhase("TrafficCapture")

    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, Any) -> Unit) {
        pipeline.sendPipeline.insertPhaseBefore(ApplicationSendPipeline.ContentEncoding, phase)
        pipeline.sendPipeline.intercept(phase) { body -> handler(context, body) }
    }
}

/** Records each call the server answers, for the server console. Streamed bodies are recorded without their content. */
internal val ServerTrafficCapture = createApplicationPlugin("ServerTrafficCapture", ::TrafficCaptureConfig) {
    val recorder = pluginConfig.recorder
    onCall { call -> call.attributes.put(StartedAt, System.currentTimeMillis()) }
    on(BeforeContentEncoding) { call, content ->
        if (content is OutgoingContent.ByteArrayContent && TrafficMasking.isText(content.contentType)) {
            call.attributes.put(Body, TrafficMasking.capped(content.bytes().decodeToString()))
        }
    }
    on(ResponseSent) { call ->
        val started = call.attributes.getOrNull(StartedAt) ?: System.currentTimeMillis()
        recorder.record(
            HttpExchange(
                startedAt = started,
                direction = HttpExchange.Direction.Incoming,
                method = call.request.httpMethod.value,
                url = TrafficMasking.url(call.request.uri),
                remoteAddress = call.request.origin.remoteAddress,
                status = call.response.status()?.value,
                durationMs = System.currentTimeMillis() - started,
                requestHeaders = TrafficMasking.headers(call.request.headers),
                responseHeaders = TrafficMasking.headers(call.response.headers.allValues()),
                contentType = call.response.headers[HttpHeaders.ContentType],
                responseBody = call.attributes.getOrNull(Body)?.ifEmpty { null },
            )
        )
    }
}
