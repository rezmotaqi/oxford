package com.example.hermesclient.data.remote.sse

import com.example.hermesclient.core.network.HermesTransportException
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class RawHermesEvent(
    val name: String?,
    val data: String,
)

interface HermesSseDataSource {
    fun stream(url: String, requestJson: String? = null): Flow<RawHermesEvent>
}

class OkHttpHermesSseDataSource @Inject constructor(
    private val client: OkHttpClient,
) : HermesSseDataSource {
    override fun stream(url: String, requestJson: String?): Flow<RawHermesEvent> = callbackFlow {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
        val request = if (requestJson == null) {
            requestBuilder.get().build()
        } else {
            requestBuilder.post(requestJson.toRequestBody(JSON_MEDIA_TYPE)).build()
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(RawHermesEvent(name = type, data = data))
                if (type == DONE_EVENT) close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val failure = HermesTransportException(
                    statusCode = response?.takeUnless(Response::isSuccessful)?.code,
                    cause = t,
                )
                response?.close()
                close(failure)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private companion object {
        const val DONE_EVENT = "done"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
