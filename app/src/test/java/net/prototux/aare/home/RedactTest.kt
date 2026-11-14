package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test
    fun credentialHeadersAreMasked() {
        assertEquals("Authorization: ***", Redact.header(Header("Authorization", "Bearer abc")))
        assertEquals("X-API-Key: ***", Redact.header(Header("X-API-Key", "abc")))
        assertEquals("Cookie: ***", Redact.header(Header("Cookie", "s=1")))
        assertEquals("Accept: application/json", Redact.header(Header("Accept", "application/json")))
    }

    @Test
    fun urlsLoseUserInfoSecretParametersAndWebhookIds() {
        assertEquals("https://***@ha.local/api", Redact.url("https://jo:pw@ha.local/api"))
        assertEquals("https://ha.local/api/webhook/***", Redact.url("https://ha.local/api/webhook/gate-123"))
        assertEquals("https://ha.local/api/webhook/***?x=1", Redact.url("https://ha.local/api/webhook/gate-123?x=1"))
        assertEquals("https://e.org/v?token=***&room=1&api_key=***#top", Redact.url("https://e.org/v?token=abc&room=1&api_key=k#top"))
        assertEquals("/api/states/sensor.t", Redact.url("/api/states/sensor.t"))
    }

    @Test
    fun knownSecretsAreMaskedInFreeText() {
        val msg = Redact.text("connect to broker failed: pw=hunter22 for jo", listOf("hunter22", "", "ab"))
        assertEquals("connect to broker failed: pw=*** for jo", msg)
        assertFalse("hunter22" in msg)
        assertEquals("failed: POST https://ha/api/webhook/*** gave 500", Redact.webhooks("failed: POST https://ha/api/webhook/gate-1 gave 500"))
    }
}
