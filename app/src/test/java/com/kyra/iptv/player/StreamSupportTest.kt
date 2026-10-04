package com.kyra.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSupportTest {
    @Test fun acceptsHttpAndHttps() {
        assertTrue(StreamSupport.isPlayable("http://host/live/1.ts"))
        assertTrue(StreamSupport.isPlayable("https://host:8080/a.m3u8?token=x"))
        assertTrue(StreamSupport.isPlayable("HTTPS://HOST/a"))
        assertTrue(StreamSupport.isPlayable("  http://host/a  "))
    }

    @Test fun rejectsOtherSchemesAndMalformedUrls() {
        assertFalse(StreamSupport.isPlayable(""))
        assertFalse(StreamSupport.isPlayable("rtmp://host/live"))
        assertFalse(StreamSupport.isPlayable("file:///sdcard/a.m3u8"))
        assertFalse(StreamSupport.isPlayable("javascript:alert(1)"))
        assertFalse(StreamSupport.isPlayable("http://"))
        assertFalse(StreamSupport.isPlayable("http:///path"))
        assertFalse(StreamSupport.isPlayable("http://host/a b"))
        assertFalse(StreamSupport.isPlayable("http://host/a\u0000"))
        assertFalse(StreamSupport.isPlayable("/relative/path.m3u8"))
    }

    @Test fun detectsHls() {
        assertEquals(StreamType.HLS, StreamSupport.detectType("http://h/live/index.m3u8"))
        assertEquals(StreamType.HLS, StreamSupport.detectType("http://h/live/INDEX.M3U8?token=1"))
        assertEquals(StreamType.HLS, StreamSupport.detectType("http://h/get.php?u=1&output=a.m3u8"))
    }

    @Test fun detectsDash() {
        assertEquals(StreamType.DASH, StreamSupport.detectType("https://h/manifest.mpd"))
        assertEquals(StreamType.DASH, StreamSupport.detectType("https://h/manifest.mpd?x=1#frag"))
    }

    @Test fun otherUrlsAreUnknownSoThePlayerSniffs() {
        assertEquals(StreamType.UNKNOWN, StreamSupport.detectType("http://h/live/user/pass/123"))
        assertEquals(StreamType.UNKNOWN, StreamSupport.detectType("http://h/live/123.ts"))
        assertEquals(StreamType.UNKNOWN, StreamSupport.detectType("http://h/movie.mp4"))
    }

    @Test fun sanitizeKeepsValidHeaders() {
        val out = StreamSupport.sanitizeHeaders(
            linkedMapOf("User-Agent" to " VLC/3.0 ", "Referer" to "http://site/", "X-Custom_1" to "ok")
        )
        assertEquals(listOf("User-Agent", "Referer", "X-Custom_1"), out.keys.toList())
        assertEquals("VLC/3.0", out["User-Agent"])
    }

    @Test fun sanitizeDropsInvalidHeaders() {
        val out = StreamSupport.sanitizeHeaders(
            linkedMapOf(
                "" to "x",
                "Bad Name" to "x",
                "Bad:Name" to "x",
                "Empty" to "  ",
                "Injected" to "a\r\nX-Evil: 1",
                "Good" to "v",
            )
        )
        assertEquals(mapOf("Good" to "v"), out)
    }
}
