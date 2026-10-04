package com.kyra.iptv.data.parser

import com.kyra.iptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Etapa 6: o M3U gerado por "Salvar funcionais" tem de voltar igual pelo parser. */
class M3uExporterTest {

    private fun export(vararg channels: Channel): String {
        val sb = StringBuilder()
        M3uExporter.write(channels.toList(), sb)
        return sb.toString()
    }

    private fun roundTrip(vararg channels: Channel): List<Channel> =
        M3uParser.parse(export(*channels)).channels

    private fun ch(
        url: String,
        name: String = "Canal",
        group: String = Channel.UNGROUPED,
        tvgId: String? = null,
        tvgName: String? = null,
        logo: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = Channel(com.kyra.iptv.data.parser.ChannelId.fromStreamUrl(url), name, url, group, tvgId, tvgName, logo, headers)

    @Test fun startsWithExtM3uAndReturnsCount() {
        val sb = StringBuilder()
        val n = M3uExporter.write(listOf(ch("http://h.example/1"), ch("http://h.example/2")), sb)
        assertEquals(2, n)
        assertTrue(sb.startsWith("#EXTM3U\n"))
    }

    @Test fun roundTripKeepsAllFields() {
        val original = ch(
            "http://h.example/live/1.m3u8", name = "Globo HD", group = "Notícias",
            tvgId = "globo.br", tvgName = "Globo HD", logo = "http://h.example/globo.png",
        )
        val back = roundTrip(original).single()
        assertEquals(original, back)
    }

    @Test fun idStaysTheHashOfTheUrl() {
        val original = ch("http://h.example/x")
        assertEquals(original.id, roundTrip(original).single().id)
    }

    @Test fun ungroupedChannelStaysUngrouped() {
        assertEquals(Channel.UNGROUPED, roundTrip(ch("http://h.example/x")).single().group)
        assertFalse("group-title" in export(ch("http://h.example/x")))
    }

    @Test fun headersRoundTrip() {
        val original = ch(
            "http://h.example/x",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11)", "Referer" to "http://r.example/", "Origin" to "http://o.example",
                "X-Token" to "a b&c=d",
            ),
        )
        val back = roundTrip(original).single()
        assertEquals(original.headers, back.headers)
    }

    @Test fun forbiddenAndInvalidHeadersAreDropped() {
        val text = export(ch("http://h.example/x", headers = mapOf("Host" to "evil", "Bad Name" to "v", "X-Ok" to "1")))
        assertFalse("evil" in text)
        assertFalse("Bad Name" in text)
        assertEquals(mapOf("X-Ok" to "1"), M3uParser.parse(text).channels.single().headers)
    }

    @Test fun quotesInAttributesBecomeApostrophesAndNothingBreaks() {
        val back = roundTrip(ch("http://h.example/x", name = "Canal", group = "Os \"Melhores\"", tvgId = "a\"b")).single()
        assertEquals("Os 'Melhores'", back.group)
        assertEquals("a'b", back.tvgId)
        assertEquals("http://h.example/x", back.streamUrl)
    }

    @Test fun lineBreaksInNamesCannotInjectEntries() {
        val text = export(ch("http://h.example/x", name = "A\nhttp://evil.example/y\n#EXTINF:-1,B"))
        val parsed = M3uParser.parse(text).channels
        assertEquals(1, parsed.size)
        assertEquals("http://h.example/x", parsed.single().streamUrl)
    }

    @Test fun nameWithCommaSurvives() {
        assertEquals("Esporte, ao vivo", roundTrip(ch("http://h.example/x", name = "Esporte, ao vivo")).single().name)
    }

    @Test fun unusableUrlsAreSkipped() {
        val sb = StringBuilder()
        val n = M3uExporter.write(listOf(ch("ftp://h.example/x"), ch("http://h.example/ok")), sb)
        assertEquals(1, n)
        assertEquals(1, M3uParser.parse(sb.toString()).channels.size)
    }

    @Test fun emptyInputGivesOnlyTheHeader() {
        val sb = StringBuilder()
        assertEquals(0, M3uExporter.write(emptyList(), sb))
        assertEquals("#EXTM3U\n", sb.toString())
    }

    @Test fun largeListRoundTrips() {
        val list = (0 until 5_000).map { ch("http://h.example/$it.m3u8", name = "C$it", group = "G${it % 7}") }
        val sb = StringBuilder()
        M3uExporter.write(list, sb)
        assertEquals(list, M3uParser.parse(sb.toString()).channels)
    }
}
