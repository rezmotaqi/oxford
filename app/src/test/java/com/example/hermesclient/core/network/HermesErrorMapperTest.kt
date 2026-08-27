package com.example.hermesclient.core.network

import com.example.hermesclient.domain.model.HermesError
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesErrorMapperTest {
    private val mapper = HermesErrorMapper()

    @Test
    fun `maps authentication and server responses`() {
        assertSame(HermesError.Unauthorized, mapper.map(HermesTransportException(401)))
        assertEquals(HermesError.ServerError(500), mapper.map(HermesTransportException(500)))
    }

    @Test
    fun `maps network failures without exposing implementation errors`() {
        assertSame(HermesError.Timeout, mapper.map(SocketTimeoutException()))
        assertSame(HermesError.Unreachable, mapper.map(UnknownHostException()))
        assertTrue(mapper.map(IllegalStateException("internal")) is HermesError.Unknown)
    }
}
