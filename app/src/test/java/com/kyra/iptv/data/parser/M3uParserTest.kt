package com.kyra.iptv.data.parser

import com.kyra.iptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    // ---- básicos ------------------------------------------------------------

    @Test fun minimalValid() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a.br" tvg-name="Canal A" tvg-logo="http://x/a.png" group-title="Esportes",Canal A HD
            http://host/a.m3u8
            """.trimIndent()
        )
        assertEquals(1, r.channels.size)
        val c = r.channels[0]
        assertEquals("Canal A", c.name) // tvg-name tem prioridade
        assertEquals("a.br", c.tvgId)
        assertEquals("Canal A", c.tvgName)
        assertEquals("http://x/a.png", c.logoUrl)
        assertEquals("Esportes", c.group)
        assertEquals("http://host/a.m3u8", c.streamUrl)
        assertEquals(0, r.skipped)
    }

    @Test fun multipleGroupsKeepOrder() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Filmes",F1
            http://h/1
            #EXTINF:-1 group-title="Esportes",E1
            http://h/2
            #EXTINF:-1 group-title="Filmes",F2
            http://h/3
            """.trimIndent()
        )
        assertEquals(listOf("F1", "E1", "F2"), r.channels.map { it.name })
        assertEquals(listOf("Filmes", "Esportes", "Filmes"), r.channels.map { it.group })
    }

    @Test fun missingGroupBecomesUngrouped() {
        val r = M3uParser.parse("#EXTINF:-1,Sem grupo\nhttp://h/1\n#EXTINF:-1 group-title=\"\",Vazio\nhttp://h/2")
        assertEquals(listOf(Channel.UNGROUPED, Channel.UNGROUPED), r.channels.map { it.group })
        assertEquals("Ungrouped", r.channels[0].group)
    }

    @Test fun missingTvgNameUsesDisplayName() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"G\",Nome Exibido\nhttp://h/1")
        assertEquals("Nome Exibido", r.channels[0].name)
        assertNull(r.channels[0].tvgName)
    }

    @Test fun missingNameFallsBackToUrl() {
        val r = M3uParser.parse("#EXTINF:-1\nhttp://example.com/live/canal7.m3u8\n#EXTINF:-1,\nhttp://example.com/\n")
        assertEquals("example.com/canal7.m3u8", r.channels[0].name)
        assertEquals("example.com", r.channels[1].name)
    }

    @Test fun missingLogoIsNull() {
        val r = M3uParser.parse("#EXTINF:-1 tvg-logo=\"\",X\nhttp://h/1")
        assertNull(r.channels[0].logoUrl)
    }

    @Test fun extraAttributesAreIgnored() {
        val r = M3uParser.parse(
            "#EXTINF:-1 tvg-id=\"x\" tvg-country=\"BR\" tvg-language=\"pt\" catchup=\"append\" group-title=\"G\",Canal\nhttp://h/1"
        )
        assertEquals("G", r.channels[0].group)
        assertEquals("x", r.channels[0].tvgId)
    }

    // ---- aspas e variações --------------------------------------------------

    @Test fun singleQuotesUnquotedAndSpacesAroundEquals() {
        val r = M3uParser.parse("#EXTINF:-1 tvg-id='id1' group-title = 'Notícias' tvg-name=SemAspas,Display\nhttp://h/1")
        val c = r.channels[0]
        assertEquals("id1", c.tvgId)
        assertEquals("Notícias", c.group)
        assertEquals("SemAspas", c.name)
    }

    @Test fun commaInsideQuotedAttributeDoesNotSplitName() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"Filmes, Séries\",Meu Canal, HD\nhttp://h/1")
        assertEquals("Filmes, Séries", r.channels[0].group)
        assertEquals("Meu Canal, HD", r.channels[0].name)
    }

    @Test fun apostropheInNameDoesNotBreakParsing() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"G\",Reporter's Channel\nhttp://h/1")
        assertEquals("Reporter's Channel", r.channels[0].name)
    }

    @Test fun uppercaseAttributeKeysAndCrlfAndBom() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1 TVG-ID=\"k\" GROUP-TITLE=\"Up\",Canal\r\nhttp://h/1\r\n"
        val r = M3uParser.parse(text)
        assertEquals("Up", r.channels[0].group)
        assertEquals("k", r.channels[0].tvgId)
    }

    // ---- URLs ---------------------------------------------------------------

    @Test fun httpAndHttps() {
        val r = M3uParser.parse("#EXTINF:-1,A\nhttp://h/a\n#EXTINF:-1,B\nhttps://h/b?token=1&x=2")
        assertEquals(listOf("http://h/a", "https://h/b?token=1&x=2"), r.channels.map { it.streamUrl })
    }

    @Test fun relativeUrlsWithBase() {
        val r = M3uParser.parse(
            "#EXTINF:-1,A\nstream/a.m3u8\n#EXTINF:-1,B\n/live/b.m3u8\n#EXTINF:-1,C\n//cdn.x/c.m3u8",
            baseUrl = "https://example.com/lists/main.m3u"
        )
        assertEquals(
            listOf(
                "https://example.com/lists/stream/a.m3u8",
                "https://example.com/live/b.m3u8",
                "https://cdn.x/c.m3u8",
            ),
            r.channels.map { it.streamUrl }
        )
    }

    @Test fun relativeUrlWithoutBaseIsSkipped() {
        val r = M3uParser.parse("#EXTINF:-1,A\nstream/a.m3u8\n#EXTINF:-1,B\nhttp://h/b")
        assertEquals(listOf("B"), r.channels.map { it.name })
        assertEquals(1, r.skipped)
    }

    @Test fun unsupportedSchemesAreSkipped() {
        val r = M3uParser.parse("#EXTINF:-1,A\nrtmp://h/a\n#EXTINF:-1,B\nfile:///etc/passwd\n#EXTINF:-1,C\nhttp://h/c")
        assertEquals(listOf("C"), r.channels.map { it.name })
        assertEquals(2, r.skipped)
    }

    // ---- entradas ruins -----------------------------------------------------

    @Test fun extinfWithoutUrlIsSkippedAndNextEntryStillParses() {
        val r = M3uParser.parse("#EXTINF:-1,Sem URL\n#EXTINF:-1,Com URL\nhttp://h/1")
        assertEquals(listOf("Com URL"), r.channels.map { it.name })
        assertEquals(1, r.skipped)
    }

    @Test fun trailingExtinfWithoutUrlIsSkipped() {
        val r = M3uParser.parse("#EXTINF:-1,A\nhttp://h/a\n#EXTINF:-1,Final sem URL")
        assertEquals(1, r.channels.size)
        assertEquals(1, r.skipped)
    }

    @Test fun commentLineBetweenExtinfAndUrl() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"G\",A\n# comentário qualquer\n#EXTGRP:Outro\nhttp://h/a")
        assertEquals(1, r.channels.size)
        assertEquals("G", r.channels[0].group)
        assertEquals("A", r.channels[0].name)
    }

    @Test fun malformedEntriesDoNotInvalidateOthers() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Bom 1
            http://h/1
            #EXTINF:lixo "quebrado=,,,
            isto não é url
            #EXTINF
            http://
            #EXTINF:-1,Bom 2
            http://h/2
            """.trimIndent()
        )
        assertEquals(listOf("Bom 1", "Bom 2"), r.channels.filter { it.name.startsWith("Bom") }.map { it.name })
        assertTrue(r.skipped >= 2)
    }

    @Test fun emptyAndNonM3uInput() {
        assertEquals(0, M3uParser.parse("").channels.size)
        assertEquals(0, M3uParser.parse("   \n\n  ").channels.size)
        assertEquals(0, M3uParser.parse("#EXTM3U\n").channels.size)
        assertEquals(0, M3uParser.parse("<html>não é playlist</html>").channels.size)
    }

    @Test fun bareUrlWithoutExtinfIsAccepted() {
        val r = M3uParser.parse("#EXTM3U\nhttp://h/live/solto.m3u8")
        assertEquals(1, r.channels.size)
        assertEquals("h/solto.m3u8", r.channels[0].name)
        assertEquals(Channel.UNGROUPED, r.channels[0].group)
    }

    // ---- duplicações e identidade -------------------------------------------

    @Test fun duplicateUrlsKeepFirst() {
        val r = M3uParser.parse("#EXTINF:-1,Primeiro\nhttp://h/1\n#EXTINF:-1,Repetido\nhttp://h/1\n#EXTINF:-1,Outro\nhttp://h/2")
        assertEquals(listOf("Primeiro", "Outro"), r.channels.map { it.name })
        assertEquals(1, r.duplicates)
    }

    @Test fun idIsDeterministicAcrossParsesAndIndependentOfMetadata() {
        val a = M3uParser.parse("#EXTINF:-1 tvg-id=\"x\",Nome A\nhttp://h/1").channels[0]
        val b = M3uParser.parse("#EXTINF:-1,Nome Renomeado\n#EXTM3U\nhttp://h/1").channels[0]
        val c = M3uParser.parse("#EXTINF:-1,Nome A\nhttp://h/2").channels[0]
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
        assertEquals(32, a.id.length)
        assertEquals(ChannelId.fromStreamUrl("http://h/1"), a.id)
    }

    @Test fun sameTvgIdWithDifferentUrlsAreDifferentChannels() {
        val r = M3uParser.parse("#EXTINF:-1 tvg-id=\"x\",HD\nhttp://h/hd\n#EXTINF:-1 tvg-id=\"x\",SD\nhttp://h/sd")
        assertEquals(2, r.channels.size)
        assertNotEquals(r.channels[0].id, r.channels[1].id)
    }

    // ---- headers ------------------------------------------------------------

    @Test fun vlcOptHeaders() {
        val r = M3uParser.parse(
            "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=MeuAgent/1.0\n#EXTVLCOPT:http-referrer=http://ref.com/\nhttp://h/a\n#EXTINF:-1,B\nhttp://h/b"
        )
        assertEquals(mapOf("User-Agent" to "MeuAgent/1.0", "Referer" to "http://ref.com/"), r.channels[0].headers)
        assertTrue(r.channels[1].headers.isEmpty()) // headers não vazam para a próxima entrada
    }

    @Test fun pipeHeaders() {
        val r = M3uParser.parse("#EXTINF:-1,A\nhttp://h/a.m3u8|User-Agent=Foo%20Bar&Referer=http://r/")
        val c = r.channels[0]
        assertEquals("http://h/a.m3u8", c.streamUrl)
        assertEquals("Foo Bar", c.headers["User-Agent"])
        assertEquals("http://r/", c.headers["Referer"])
    }

    // ---- tamanhos: pequena, média, grande -----------------------------------

    private fun generate(n: Int, withNoise: Boolean = false): String = buildString {
        append("#EXTM3U\n")
        for (i in 0 until n) {
            append("#EXTINF:-1 tvg-id=\"id$i\" tvg-name=\"Canal $i\" tvg-logo=\"http://l/$i.png\" group-title=\"Grupo ${i % 25}\",Canal $i\n")
            if (withNoise && i % 100 == 0) append("# ruído\n#EXTINF:-1,Quebrado sem url\n")
            append("http://host.example/live/$i.m3u8\n")
        }
    }

    @Test fun smallPlaylist() {
        val r = M3uParser.parse(generate(10))
        assertEquals(10, r.channels.size)
    }

    @Test fun mediumPlaylist() {
        val r = M3uParser.parse(generate(5_000))
        assertEquals(5_000, r.channels.size)
        assertEquals(25, r.channels.map { it.group }.toSet().size)
        assertEquals("Canal 4999", r.channels.last().name)
    }

    @Test fun largePlaylistWithNoise() {
        val n = 100_000
        val text = generate(n, withNoise = true)
        val start = System.nanoTime()
        val r = M3uParser.parse(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(n, r.channels.size)
        assertEquals(n / 100, r.skipped) // as 1000 entradas quebradas, e só elas
        assertTrue("demorou ${ms}ms", ms < 10_000)
    }
}
