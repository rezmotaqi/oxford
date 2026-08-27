package com.example.hermesclient.data.remote.mapper

import com.example.hermesclient.data.remote.dto.CapabilitiesDto
import com.example.hermesclient.data.remote.dto.CapabilityFeaturesDto
import com.example.hermesclient.data.remote.dto.MessageDto
import com.example.hermesclient.data.remote.dto.SessionDto
import com.example.hermesclient.domain.model.MessageRole
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesMappersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `maps capability feature names into stable domain model`() {
        val result = CapabilitiesDto(
            features = CapabilityFeaturesDto(
                sessionResources = true,
                sessionChatStreaming = true,
                runSubmission = true,
                runStatus = true,
                runEventsSse = true,
                runStop = true,
                runApprovalResponse = true,
                approvalEvents = false,
            ),
        ).toDomain()

        assertTrue(result.sessionsSupported)
        assertTrue(result.sessionStreamingSupported)
        assertTrue(result.runsSupported)
        assertFalse(result.approvalsSupported)
    }

    @Test
    fun `maps epoch seconds and message content`() {
        val session = SessionDto(
            id = "session-1",
            title = "Build Android client",
            startedAt = 1_700_000_000.5,
            lastActive = 1_700_000_100.0,
        ).toDomain()
        val message = MessageDto(
            id = JsonPrimitive(42),
            role = "assistant",
            content = json.parseToJsonElement(
                """[{"type":"text","text":"Hel"},{"type":"output_text","text":"lo"}]""",
            ),
            timestamp = 1_700_000_101.0,
        ).toDomain()

        assertEquals(Instant.ofEpochSecond(1_700_000_000, 500_000_000), session.createdAt)
        assertEquals("42", message.id)
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals("Hello", message.content)
    }
}
