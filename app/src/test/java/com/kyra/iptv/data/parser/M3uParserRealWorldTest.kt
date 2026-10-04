package com.kyra.iptv.data.parser

import com.kyra.iptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

/**
 * Variações vistas em playlists IPTV reais (Fase 7). Os textos são sintéticos: hosts `example`,
 * sem credenciais de listas de verdade (PLANO.md §13).
 */
class M3uParserRealWorldTest {

    @Test fun xtreamStyleListWithPipesAndCredentialsInPath() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="" tvg-name="BR| CANAL 1 HD" tvg-logo="http://logo.example/1.png" group-title="BR | ABERTOS",BR| CANAL 1 HD
            http://painel.example:8080/live/usuario/senha/101.ts
            #EXTINF:-1 tvg-id="" tvg-name="BR| CANAL 2 FHD" tvg-logo="" group-title="BR | ABERTOS",BR| CANAL 2 FHD
            http://painel.example:8080/live/usuario/senha/102.ts
            #EXTINF:-1 tvg-id="" tvg-name="UK| NEWS" tvg-logo="" group-title="UK | NEWS",UK| NEWS
            http://painel.example:8080/live/usuario/senha/201.ts
            """.trimIndent()
        )
        assertEquals(3, r.channels.size)
        assertEquals("BR| CANAL 1 HD", r.channels[0].name)
        assertEquals(listOf("BR | ABERTOS", "BR | ABERTOS", "UK | NEWS"), r.channels.map { it.group })
        assertNull(r.channels[0].tvgId) // tvg-id="" vira ausente
        assertNull(r.channels[1].logoUrl) // tvg-logo="" vira ausente
        assertEquals("http://painel.example:8080/live/usuario/senha/101.ts", r.channels[0].streamUrl)
    }

    @Test fun crlfBomTabsAndTrailingSpaces() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"A\",Um  \t\r\nhttp://h.example/1  \r\n\r\n#EXTINF:-1,Dois\r\n\thttp://h.example/2\t\r\n"
        val r = M3uParser.parse(text)
        assertEquals(listOf("Um", "Dois"), r.channels.map { it.name })
        assertEquals(listOf("http://h.example/1", "http://h.example/2"), r.channels.map { it.streamUrl })
    }

    @Test fun loneCarriageReturnLineEndings() {
        val r = M3uParser.parse("#EXTM3U\r#EXTINF:-1,Um\rhttp://h.example/1\r#EXTINF:-1,Dois\rhttp://h.example/2\r")
        assertEquals(listOf("Um", "Dois"), r.channels.map { it.name })
    }

    @Test fun extGrpFillsGroupWhenThereIsNoGroupTitle() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Com EXTGRP
            #EXTGRP:Esportes
            http://h.example/1
            #EXTINF:-1 group-title="Filmes",Com os dois
            #EXTGRP:Esportes
            http://h.example/2
            #EXTINF:-1,Sem nada
            http://h.example/3
            """.trimIndent()
        )
        assertEquals(listOf("Esportes", "Filmes", Channel.UNGROUPED), r.channels.map { it.group })
    }

    @Test fun commaInsideTvgNameAndEqualsInDisplayName() {
        val r = M3uParser.parse(
            "#EXTINF:-1 tvg-name=\"Canal A, B\" group-title=\"X\",Canal = Teste\nhttp://h.example/1\n" +
                "#EXTINF:-1 group-title=\"X\",Canal \"HD\" = 2\nhttp://h.example/2"
        )
        assertEquals("Canal A, B", r.channels[0].name)
        assertEquals("Canal \"HD\" = 2", r.channels[1].name)
    }

    @Test fun accentsAndEmojiInGroupsSurvive() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"Esportes ⚽\",Ação\nhttp://h.example/1\n#EXTINF:-1 group-title=\"Notícias\",Jornal\nhttp://h.example/2")
        assertEquals(listOf("Esportes ⚽", "Notícias"), r.channels.map { it.group })
        assertEquals("Ação", r.channels[0].name)
    }

    @Test fun durationVariantsAndSpacesAfterColon() {
        val r = M3uParser.parse("#EXTINF:-1.0,A\nhttp://h.example/1\n#EXTINF:0,B\nhttp://h.example/2\n#EXTINF: -1 ,C\nhttp://h.example/3\n#extinf:-1,D\nhttp://h.example/4")
        assertEquals(listOf("A", "B", "C", "D"), r.channels.map { it.name })
    }

    @Test fun nonHttpSchemesAreSkippedAndCounted() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,RTMP
            rtmp://h.example/live/1
            #EXTINF:-1,RTSP
            rtsp://h.example/1
            #EXTINF:-1,UDP
            udp://@239.0.0.1:1234
            #EXTINF:-1,MMS
            mms://h.example/1
            #EXTINF:-1,Arquivo
            file:///sdcard/x.ts
            #EXTINF:-1,OK
            https://h.example/ok.m3u8
            """.trimIndent()
        )
        assertEquals(listOf("OK"), r.channels.map { it.name })
        assertEquals(5, r.skipped)
    }

    @Test fun vlcOptAndPipeHeadersTogether() {
        val r = M3uParser.parse(
            "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11)\n#EXTVLCOPT:http-referrer=http://ref.example/\nhttp://h.example/a.m3u8|Origin=http%3A%2F%2Forigin.example\n"
        )
        val h = r.channels.single().headers
        assertEquals("Mozilla/5.0 (X11)", h["User-Agent"])
        assertEquals("http://ref.example/", h["Referer"])
        assertEquals("http://origin.example", h["Origin"])
        assertEquals("http://h.example/a.m3u8", r.channels.single().streamUrl) // o "|..." não vai para a URL
    }

    @Test fun veryLongNameAndAttributeDoNotBreakParsing() {
        val longName = "x".repeat(1_000_000)
        val longAttr = "y".repeat(200_000)
        val r = M3uParser.parse("#EXTINF:-1 tvg-logo=\"http://l.example/$longAttr\" group-title=\"G\",$longName\nhttp://h.example/1\n#EXTINF:-1,Depois\nhttp://h.example/2")
        assertEquals(2, r.channels.size)
        assertEquals(longName.length, r.channels[0].name.length)
        assertEquals("Depois", r.channels[1].name)
    }

    @Test fun htmlErrorPageAndBinaryNoiseYieldNoChannelsWithoutThrowing() {
        val html = "<!DOCTYPE html><html><head><title>Login</title></head><body><a href=\"http://h.example/\">entrar</a></body></html>"
        assertTrue(M3uParser.parse(html).channels.isEmpty())
        val noise = "\u0000\u0001\u0002\n\uFFFD\uFFFD\n#EXTINF\n\u0000"
        assertTrue(M3uParser.parse(noise).channels.isEmpty())
    }

    @Test fun sameGroupIsASingleStringInstance() {
        val r = M3uParser.parse("#EXTINF:-1 group-title=\"Filmes\",A\nhttp://h.example/1\n#EXTINF:-1 group-title=\"Filmes\",B\nhttp://h.example/2")
        assertSame(r.channels[0].group, r.channels[1].group)
    }

    // ---- stream HLS colado como se fosse lista -------------------------------

    @Test fun hlsMediaPlaylistIsRecognisedAsAStreamNotAChannelList() {
        val stats = M3uParser.scan(
            java.io.StringReader(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nseg1.ts\n#EXTINF:10.0,\nseg2.ts\n"
            ),
            "http://h.example/live/index.m3u8",
        ) {}
        assertTrue(stats.looksLikeHlsStream)
    }

    @Test fun hlsMasterPlaylistIsRecognisedAsAStream() {
        val stats = M3uParser.scan(
            java.io.StringReader("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n"),
            "http://h.example/live/master.m3u8",
        ) {}
        assertTrue(stats.looksLikeHlsStream)
    }

    @Test fun iptvListWithAStrayHlsTagIsStillAChannelList() {
        val stats = M3uParser.scan(
            java.io.StringReader("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",A\nhttp://h.example/1\n"),
            null,
        ) {}
        assertFalse(stats.looksLikeHlsStream)
        assertEquals(1, stats.count)
    }

    // ---- listas grandes (sem acumular) ---------------------------------------

    /** Gera a playlist aos poucos: o teste não precisa guardar dezenas de MB de texto. */
    private class GeneratedReader(private val entries: Int) : Reader() {
        private var next = 0
        private var buf = "#EXTM3U\n"
        private var pos = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= buf.length) {
                if (next >= entries) return -1
                buf = "#EXTINF:-1 tvg-id=\"i$next\" group-title=\"G${next % 50}\",Canal $next\nhttp://h.example/live/$next.ts\n"
                next++
                pos = 0
            }
            val n = minOf(len, buf.length - pos)
            buf.toCharArray(cbuf, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {}
    }

    @Test fun scanCountsHugePlaylistWithoutKeepingChannels() {
        var seen = 0
        val stats = M3uParser.scan(GeneratedReader(300_000)) { seen++ }
        assertEquals(300_000, stats.count)
        assertEquals(300_000, seen)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.duplicates)
    }

    @Test fun scanStopsWhenTheCallbackThrows() {
        var seen = 0
        try {
            M3uParser.scan(GeneratedReader(300_000)) {
                seen++
                if (seen == 10) throw IllegalStateException("limite")
            }
            throw AssertionError("deveria ter lançado")
        } catch (e: IllegalStateException) {
            assertEquals(10, seen) // parou na hora, sem ler o resto
        }
    }
}
