package com.kyra.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 7: URLs inválidas e headers hostis/malformados antes de chegarem ao player. */
class StreamSupportRobustnessTest {

    @Test fun malformedUrlsAreNotPlayable() {
        val bad = listOf(
            "   ", "http://?q=1", "http://#frag", "http:/h.example/a", "://h.example/a", "ht tp://h.example/a",
            "http://ho st/a", "http://h.example/a\tb", "http://h.example/a\nb", "http://h.example/\u0007",
            "data:text/plain;base64,AAAA", "content://media/external/video/1", "intent://scan/#Intent;end",
        )
        for (url in bad) assertFalse("não deveria tocar: $url", StreamSupport.isPlayable(url))
    }

    @Test fun unusualButValidUrlsArePlayable() {
        assertTrue(StreamSupport.isPlayable("http://[::1]:8080/live/1.ts"))
        assertTrue(StreamSupport.isPlayable("http://usuario:senha@h.example/live/1.ts"))
        assertTrue(StreamSupport.isPlayable("https://h.example/a.m3u8?token=abc%20def&x=1#frag"))
        assertTrue(StreamSupport.isPlayable("http://h.example/" + "a".repeat(8_000)))
    }

    @Test fun validHeadersAreKeptTrimmedAndInOrder() {
        val out = StreamSupport.sanitizeHeaders(
            linkedMapOf(" User-Agent " to " Mozilla/5.0 ", "Referer" to "http://ref.example/", "X-Token" to "abc")
        )
        assertEquals(listOf("User-Agent", "Referer", "X-Token"), out.keys.toList())
        assertEquals("Mozilla/5.0", out["User-Agent"])
    }

    @Test fun headerInjectionAndGarbageAreDropped() {
        val out = StreamSupport.sanitizeHeaders(
            mapOf(
                "X-Evil" to "ok\r\nInjected: 1",
                "X-Nul" to "a\u0000b",
                "Bad Name" to "v",
                "Bad:Name" to "v",
                "" to "v",
                "X-Empty" to "   ",
                "X-Good" to "v",
            )
        )
        assertEquals(mapOf("X-Good" to "v"), out)
    }

    @Test fun headersOwnedByTheHttpStackAreDroppedCaseInsensitively() {
        val out = StreamSupport.sanitizeHeaders(
            mapOf(
                "Host" to "outro.example",
                "content-length" to "0",
                "TRANSFER-ENCODING" to "chunked",
                "Connection" to "close",
                "Upgrade" to "websocket",
                "Expect" to "100-continue",
                "Referer" to "http://ref.example/",
            )
        )
        assertEquals(mapOf("Referer" to "http://ref.example/"), out)
    }

    @Test fun castDoesNotWarnWhenOnlyDroppedHeadersExist() {
        assertFalse(CastSupport.dependsOnHeaders(mapOf("Host" to "x", "Connection" to "close")))
        assertTrue(CastSupport.dependsOnHeaders(mapOf("User-Agent" to "x")))
    }
}
