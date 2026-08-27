package com.example.hermesclient.data.remote.sse

import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHermesEventParserTest {
    private val parser = DefaultHermesEventParser(Json { ignoreUnknownKeys = true })

    @Test
    fun `parses assistant delta using actual Hermes fields`() {
        val event = parser.parse(
            "assistant.delta",
            """{"message_id":"msg-1","delta":"Hel","run_id":"run-1","seq":2}""",
        ) as ChatEvent.AssistantDelta

        assertEquals("msg-1", event.messageId)
        assertEquals("Hel", event.text)
    }

    @Test
    fun `parses tool lifecycle without exposing arguments`() {
        val started = parser.parse(
            "tool.started",
            """{"message_id":"tool-1","tool_name":"read_file","preview":"Reading file","args":{"path":"secret"}}""",
        ) as ChatEvent.ToolStarted
        val completed = parser.parse(
            "tool.failed",
            """{"message_id":"tool-1","tool_name":"read_file","preview":"Failed"}""",
        ) as ChatEvent.ToolCompleted

        assertEquals("tool-1", started.id)
        assertEquals("read_file", started.name)
        assertEquals("Failed", completed.preview)
        assertFalse(completed.succeeded)
    }

    @Test
    fun `maps authoritative run completion messages`() {
        val event = parser.parse(
            "run.completed",
            """{
                "message_id":"msg-1",
                "completed":true,
                "messages":[
                  {"id":1,"role":"assistant","content":"Hello","timestamp":1700000000},
                  {"id":2,"role":"tool","content":"done","tool_name":"terminal"}
                ]
            }""".trimIndent(),
        ) as ChatEvent.Completed

        assertEquals(2, event.messages.size)
        assertEquals("Hello", event.messages.first().content)
        assertEquals(MessageRole.TOOL, event.messages.last().role)
        assertEquals("terminal", event.messages.last().toolName)
    }

    @Test
    fun `ignores unknown and malformed events`() {
        assertNull(parser.parse("future.event", "{}"))
        assertNull(parser.parse("assistant.delta", "not-json"))
    }

    @Test
    fun `maps server error to safe stream failure`() {
        val event = parser.parse("error", """{"message":"Run interrupted"}""")

        assertTrue(event is ChatEvent.Failure)
    }

    @Test
    fun `parses data-only run events and approval choices`() {
        val delta = parser.parse(
            null,
            """{"event":"message.delta","run_id":"run-1","delta":"Hi"}""",
        ) as ChatEvent.AssistantDelta
        val approval = parser.parse(
            null,
            """{
                "event":"approval.request",
                "run_id":"run-1",
                "command":"rm temp.txt",
                "description":"Delete a temporary file",
                "choices":["once","deny"]
            }""".trimIndent(),
        ) as ChatEvent.ApprovalRequested

        assertEquals("Hi", delta.text)
        assertEquals("rm temp.txt", approval.command)
        assertEquals(setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY), approval.choices)
    }

    @Test
    fun `maps run completion output and cancellation`() {
        val completed = parser.parse(
            null,
            """{"event":"run.completed","run_id":"run-1","output":"Finished"}""",
        ) as ChatEvent.Completed
        val stopped = parser.parse(
            null,
            """{"event":"run.cancelled","run_id":"run-1"}""",
        ) as ChatEvent.Stopped

        assertEquals("Finished", completed.messages.single().content)
        assertEquals("run-1", stopped.runId)
    }
}
