package com.example.hermesclient.core.network

import com.example.hermesclient.domain.model.HermesError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    private val normalizer = UrlNormalizer()

    @Test
    fun `normalizes trailing slashes and casing`() {
        val result = normalizer.normalize("  HTTPS://hermes.example.com/api///  ", false)

        assertEquals("https://hermes.example.com/api", result.getOrThrow())
    }

    @Test
    fun `rejects cleartext when not explicitly allowed`() {
        val error = normalizer.normalize("http://10.0.2.2:8642", false).exceptionOrNull()

        assertTrue(error is HermesError.InvalidUrl)
    }

    @Test
    fun `allows emulator cleartext in debug policy`() {
        val result = normalizer.normalize("http://10.0.2.2:8642/", true)

        assertEquals("http://10.0.2.2:8642", result.getOrThrow())
    }

    @Test
    fun `rejects credentials and query parameters`() {
        assertTrue(
            normalizer.normalize("https://user:secret@example.com", false).exceptionOrNull()
                is HermesError.InvalidUrl,
        )
        assertTrue(
            normalizer.normalize("https://example.com?key=secret", false).exceptionOrNull()
                is HermesError.InvalidUrl,
        )
    }
}
