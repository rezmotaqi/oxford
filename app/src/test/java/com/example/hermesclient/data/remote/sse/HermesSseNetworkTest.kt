package com.example.hermesclient.data.remote.sse

import com.example.hermesclient.core.network.ApiKeyProvider
import com.example.hermesclient.core.network.AuthenticationInterceptor
import com.example.hermesclient.core.network.ConnectionRuntime
import com.example.hermesclient.core.network.EndpointResolver
import com.example.hermesclient.core.network.HermesErrorMapper
import com.example.hermesclient.core.network.HermesHttpClientFactory
import com.example.hermesclient.core.network.HermesTransportException
import com.example.hermesclient.data.repository.HermesChatRepository
import com.example.hermesclient.data.remote.api.HermesApiFactory
import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.HermesConnectionConfig
import com.example.hermesclient.domain.model.HermesError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesSseNetworkTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `normal stream posts authentication and closes on done`() = runBlocking {
        server.enqueue(sseResponse(SUCCESSFUL_STREAM))
        val dataSource = OkHttpHermesSseDataSource(authenticatedClient())

        val events = withTimeout(3_000) {
            dataSource.stream(server.url("/stream").toString(), """{"input":"Hello"}""").toList()
        }

        assertEquals(listOf("assistant.delta", "run.completed"), events.map { it.name })
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `authentication and server failures retain status for domain mapping`() = runBlocking {
        listOf(401, 500).forEach { status ->
            server.enqueue(MockResponse.Builder().code(status).build())
            val failure = collectFailure(
                OkHttpHermesSseDataSource(authenticatedClient()),
                server.url("/stream-$status").toString(),
            )

            assertEquals(status, (failure as HermesTransportException).statusCode)
            val mapped = HermesErrorMapper().map(failure)
            if (status == 401) {
                assertEquals(HermesError.Unauthorized, mapped)
            } else {
                assertEquals(HermesError.ServerError(500), mapped)
            }
        }
    }

    @Test
    fun `read timeout is surfaced as a timeout domain error`() = runBlocking {
        server.enqueue(
            sseResponse(SUCCESSFUL_STREAM).newBuilder()
                .bodyDelay(1, TimeUnit.SECONDS)
                .build(),
        )
        val client = authenticatedClient().newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        val failure = collectFailure(
            OkHttpHermesSseDataSource(client),
            server.url("/slow-stream").toString(),
        )

        assertEquals(HermesError.Timeout, HermesErrorMapper().map(failure))
    }

    @Test
    fun `malformed event is ignored while valid completion still arrives`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-1","status":"started"}""")
                .build(),
        )
        server.enqueue(sseResponse(STREAM_WITH_MALFORMED_EVENT))
        val runtime = ConnectionRuntime().apply {
            update(HermesConnectionConfig(server.url("/").toString(), "test-key"))
        }
        val json = Json { ignoreUnknownKeys = true }
        val repository = HermesChatRepository(
            api = HermesApiFactory(json, HermesHttpClientFactory()).create(ApiKeyProvider { "test-key" }),
            sseDataSource = OkHttpHermesSseDataSource(authenticatedClient()),
            eventParser = DefaultHermesEventParser(json),
            connectionRuntime = runtime,
            endpointResolver = EndpointResolver(),
            errorMapper = HermesErrorMapper(),
        )

        val events = withTimeout(3_000) {
            repository.streamMessage("session-1", "Hello").toList()
        }

        assertEquals(3, events.size)
        assertTrue(events.first() is ChatEvent.RunStarted)
        assertEquals("Hi", (events[1] as ChatEvent.AssistantDelta).text)
        assertTrue(events.last() is ChatEvent.Completed)
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/v1/runs", createRequest.url.encodedPath)
        assertTrue(requireNotNull(createRequest.body).utf8().contains("\"session_id\":\"session-1\""))
        val eventsRequest = server.takeRequest()
        assertEquals("GET", eventsRequest.method)
        assertEquals("/v1/runs/run-1/events", eventsRequest.url.encodedPath)
    }

    @Test
    fun `approval and stop actions use run endpoints`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-1","choice":"once","resolved":1}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-1","status":"stopping"}""")
                .build(),
        )
        val runtime = ConnectionRuntime().apply {
            update(HermesConnectionConfig(server.url("/").toString(), "test-key"))
        }
        val json = Json { ignoreUnknownKeys = true }
        val repository = HermesChatRepository(
            api = HermesApiFactory(json, HermesHttpClientFactory()).create(ApiKeyProvider { "test-key" }),
            sseDataSource = OkHttpHermesSseDataSource(authenticatedClient()),
            eventParser = DefaultHermesEventParser(json),
            connectionRuntime = runtime,
            endpointResolver = EndpointResolver(),
            errorMapper = HermesErrorMapper(),
        )

        assertTrue(repository.respondToApproval("run-1", ApprovalChoice.ONCE).isSuccess)
        assertTrue(repository.stopRun("run-1").isSuccess)

        val approval = server.takeRequest()
        assertEquals("/v1/runs/run-1/approval", approval.url.encodedPath)
        assertEquals("{\"choice\":\"once\"}", requireNotNull(approval.body).utf8())
        val stop = server.takeRequest()
        assertEquals("/v1/runs/run-1/stop", stop.url.encodedPath)
        assertEquals("POST", stop.method)
    }

    @Test
    fun `steer action posts guidance to the active run`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-1","accepted":true}""")
                .build(),
        )
        val runtime = ConnectionRuntime().apply {
            update(HermesConnectionConfig(server.url("/").toString(), "test-key"))
        }
        val json = Json { ignoreUnknownKeys = true }
        val repository = HermesChatRepository(
            api = HermesApiFactory(json, HermesHttpClientFactory()).create(ApiKeyProvider { "test-key" }),
            sseDataSource = OkHttpHermesSseDataSource(authenticatedClient()),
            eventParser = DefaultHermesEventParser(json),
            connectionRuntime = runtime,
            endpointResolver = EndpointResolver(),
            errorMapper = HermesErrorMapper(),
        )

        assertTrue(repository.steerRun("run-1", "Prioritize the failing test.").isSuccess)

        val steer = server.takeRequest()
        assertEquals("/v1/runs/run-1/steer", steer.url.encodedPath)
        assertEquals("POST", steer.method)
        assertEquals("{\"input\":\"Prioritize the failing test.\"}", requireNotNull(steer.body).utf8())
    }

    @Test
    fun `abrupt disconnect fails the active stream`() = runBlocking {
        val partialBody = "event: assistant.delta\ndata: {\"delta\":\"Hi\"}\n\n"
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .body(partialBody)
                .setHeader("Content-Length", partialBody.length + 1_000)
                .onResponseEnd(SocketEffect.CloseSocket())
                .build(),
        )

        val failure = collectFailure(
            OkHttpHermesSseDataSource(authenticatedClient()),
            server.url("/disconnect").toString(),
        )

        assertTrue(failure is HermesTransportException)
    }

    @Test
    fun `repository stops orphaned run after event stream disconnect`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-orphan","status":"started"}""")
                .build(),
        )
        val partialBody = "data: {\"event\":\"message.delta\",\"delta\":\"Hi\"}\n\n"
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .body(partialBody)
                .setHeader("Content-Length", partialBody.length + 1_000)
                .onResponseEnd(SocketEffect.CloseSocket())
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"run_id":"run-orphan","status":"stopping"}""")
                .build(),
        )
        val runtime = ConnectionRuntime().apply {
            update(HermesConnectionConfig(server.url("/").toString(), "test-key"))
        }
        val json = Json { ignoreUnknownKeys = true }
        val repository = HermesChatRepository(
            api = HermesApiFactory(json, HermesHttpClientFactory()).create(ApiKeyProvider { "test-key" }),
            sseDataSource = OkHttpHermesSseDataSource(authenticatedClient()),
            eventParser = DefaultHermesEventParser(json),
            connectionRuntime = runtime,
            endpointResolver = EndpointResolver(),
            errorMapper = HermesErrorMapper(),
        )

        val events = withTimeout(3_000) {
            repository.streamMessage("session-1", "Hello").toList()
        }

        assertTrue(events.last() is ChatEvent.Failure)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/v1/runs/run-orphan/stop", server.takeRequest().url.encodedPath)
    }

    private fun authenticatedClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthenticationInterceptor(ApiKeyProvider { "test-key" }))
        .build()

    private suspend fun collectFailure(
        dataSource: HermesSseDataSource,
        url: String,
    ): Throwable = try {
        withTimeout(3_000) { dataSource.stream(url, """{"input":"Hello"}""").toList() }
        throw AssertionError("Expected the stream to fail")
    } catch (error: Throwable) {
        error
    }

    private fun sseResponse(body: String): MockResponse = MockResponse.Builder()
        .setHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private companion object {
        val SUCCESSFUL_STREAM = """
            event: assistant.delta
            data: {"message_id":"message-1","delta":"Hello"}

            event: run.completed
            data: {"completed":true,"messages":[]}

            event: done
            data: {}

        """.trimIndent()

        val STREAM_WITH_MALFORMED_EVENT = """
            event: assistant.delta
            data: not-json

            event: assistant.delta
            data: {"message_id":"message-1","delta":"Hi"}

            event: run.completed
            data: {"completed":true,"messages":[]}

            event: done
            data: {}

        """.trimIndent()
    }
}
