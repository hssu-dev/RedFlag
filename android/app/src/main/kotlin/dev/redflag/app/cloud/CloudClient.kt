package dev.redflag.app.cloud

import dev.redflag.app.AppGraph
import dev.redflag.app.lite.LiteObservation
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed interface CloudEvent {
    data class Trace(val step: TraceStep) : CloudEvent
    data class Report(val report: RiskReport) : CloudEvent
    data class Failed(val message: String) : CloudEvent
}

/**
 * POST {backend}/v1/analyze -> text/event-stream (`trace`* then `report` | `error`).
 * Runs in the background; never blocks page entry.
 */
class CloudClient(private val g: AppGraph) {
    private val client by lazy {
        g.http.newBuilder().readTimeout(70, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    }

    /** [userContext]: only what the user handed over (built by CloudContext, on the advanced-analysis tap only). */
    fun analyze(url: String, lite: LiteObservation?, trigger: String, userContext: kotlinx.serialization.json.JsonObject? = null): Flow<CloudEvent> = callbackFlow {
        val body = buildJsonObject {
            put("url", url)
            put("trigger", trigger)
            put("locale", if (Locale.getDefault().language == "ko") "ko" else "en")
            if (lite != null) put("lite_observation", buildJsonObject {
                put("claimed_brand", lite.claimedBrand?.let { JsonPrimitive(it) } ?: JsonNull)
                put("has_credential_form", lite.hasCredentialForm)
                put("title", lite.title?.let { JsonPrimitive(it) } ?: JsonNull)
                put("lite_score", lite.liteScore?.let { JsonPrimitive(it) } ?: JsonNull)
            }) else put("lite_observation", JsonNull)
            if (userContext != null) put("user_context", userContext)
        }.toString()
        val req = try {
            Request.Builder()
                .url(g.settings.backendUrl.trimEnd('/') + "/v1/analyze")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) { // malformed backend URL in Settings
            trySend(CloudEvent.Failed("invalid backend URL: ${g.settings.backendUrl}"))
            close()
            return@callbackFlow
        }

        val source = EventSources.createFactory(client).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val ev: CloudEvent? = runCatching {
                    when (type) {
                        "trace" -> CloudEvent.Trace(RedFlagJson.decodeFromString(TraceStep.serializer(), data))
                        "report" -> CloudEvent.Report(RedFlagJson.decodeFromString(RiskReport.serializer(), data))
                        "error" -> CloudEvent.Failed(
                            runCatching { RedFlagJson.parseToJsonElement(data).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: data,
                        )
                        else -> null
                    }
                }.getOrElse { CloudEvent.Failed("bad event: ${it.message}") }
                if (ev != null) trySend(ev)
                if (ev is CloudEvent.Report || ev is CloudEvent.Failed) close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(CloudEvent.Failed(t?.message ?: "HTTP ${response?.code}"))
                close()
            }

            override fun onClosed(eventSource: EventSource) { close() }
        })
        awaitClose { source.cancel() }
    }
}
