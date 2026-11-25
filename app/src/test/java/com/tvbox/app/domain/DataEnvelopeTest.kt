package com.tvbox.app.domain

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T7-44：schema 信封的四向判定与 ignoreUnknownKeys 生命线。 */
class DataEnvelopeTest {

    @Serializable
    data class Payload(val x: Int = 0)

    @Test
    fun `envelope verdicts cover new old and current readers`() {
        assertEquals(EnvelopeVerdict.Accept, inspectEnvelope(1, 1, Schemas.Kind.SourceConfig))
        assertEquals(EnvelopeVerdict.Migrate, inspectEnvelope(1, 1, Schemas.Kind.RemoteProfile))
        assertEquals(
            EnvelopeVerdict.RejectTooNew,
            inspectEnvelope(Schemas.MAX_READER + 1, 1, Schemas.Kind.SourceConfig),
        )
        assertEquals(
            EnvelopeVerdict.RejectTooOld,
            inspectEnvelope(1, Schemas.MAX_READER + 1, Schemas.Kind.SourceConfig),
        )
    }

    @Test
    fun `all four kinds have an explicit current schema`() {
        val kinds = Schemas.Kind.entries
        assertEquals(4, kinds.size)
        kinds.forEach { assertTrue(Schemas.current(it) in 1..Schemas.MAX_READER) }
        assertEquals(1, Schemas.current(Schemas.Kind.UpdateManifest))
        assertEquals(2, Schemas.current(Schemas.Kind.RemoteProfile))
    }

    @Test
    fun `unknown fields are ignored so adding delta cannot break old clients`() {
        val raw = """
            {"schema":1,"kind":"update_manifest","minReader":1,"createdBy":"1.0.43/20043",
             "payload":{"x":7},"delta":{"from":20042,"to":20043,"url":"https://e.example/d.bin"}}
        """.trimIndent()
        val result = UntrustedJson.decode(raw, DataEnvelope.serializer(Payload.serializer()))
        assertTrue(result.isSuccess)
        val envelope = result.getOrThrow()
        assertEquals("update_manifest", envelope.kind)
        assertEquals(7, envelope.payload.x)
        assertEquals(null, envelope.checksum)
    }
}
