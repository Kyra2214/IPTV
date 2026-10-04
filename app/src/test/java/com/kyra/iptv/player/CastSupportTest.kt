package com.kyra.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSupportTest {
    @Test fun hlsAndDashHaveTheirMimeTypes() {
        assertEquals(CastSupport.MIME_HLS, CastSupport.mimeType("http://h/live/index.m3u8?token=1"))
        assertEquals(CastSupport.MIME_DASH, CastSupport.mimeType("https://h/manifest.mpd"))
    }

    @Test fun tsAndMp4ByExtensionIgnoringQuery() {
        assertEquals(CastSupport.MIME_TS, CastSupport.mimeType("http://h/live/123.ts"))
        assertEquals(CastSupport.MIME_TS, CastSupport.mimeType("http://h/live/123.TS?x=1"))
        assertEquals(CastSupport.MIME_MP4, CastSupport.mimeType("http://h/movie.mp4"))
    }

    @Test fun extensionlessUrlsAreAssumedHls() {
        assertEquals(CastSupport.MIME_HLS, CastSupport.mimeType("http://h/live/user/pass/123"))
        assertEquals(CastSupport.MIME_HLS, CastSupport.mimeType("http://h:8080/stream"))
    }

    @Test fun mimeTypeIsNeverBlank() {
        for (u in listOf("", "x", "http://h/", "http://h/a.unknown")) {
            assertTrue(CastSupport.mimeType(u).isNotBlank())
        }
    }

    @Test fun headersMakeCastRisky() {
        assertFalse(CastSupport.dependsOnHeaders(emptyMap()))
        assertTrue(CastSupport.dependsOnHeaders(mapOf("User-Agent" to "VLC")))
        // header inválido é descartado e não conta
        assertFalse(CastSupport.dependsOnHeaders(mapOf("Bad Name" to "x", "Empty" to "")))
    }

    @Test fun pathOfStripsQueryAndFragment() {
        assertEquals("http://h/a/b.m3u8", StreamSupport.pathOf("http://H/A/B.M3U8?x=1#y"))
    }
}
