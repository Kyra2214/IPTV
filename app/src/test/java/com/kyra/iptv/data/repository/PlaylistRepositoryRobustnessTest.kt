package com.kyra.iptv.data.repository

import com.kyra.iptv.data.ChannelCatalog
import com.kyra.iptv.data.ChannelTab
import com.kyra.iptv.storage.LocalStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/** Fase 7: entradas hostis ou estranhas (codificação, limites, falhas no meio do download) sem rede real. */
class PlaylistRepositoryRobustnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("data") }
    private var now = 1_000L

    private fun repo(
        fetcher: ContentFetcher = ContentFetcher { throw IOException("sem rede neste teste") },
        maxChannels: Int = PlaylistRepository.DEFAULT_MAX_CHANNELS,
        maxLoadMs: Long = PlaylistRepository.DEFAULT_MAX_LOAD_MS,
    ) = PlaylistRepository(LocalStorage(root), fetcher, clock = { now }, maxChannels = maxChannels, maxLoadMs = maxLoadMs)

    private fun tempLeftovers() = File(root, "content").listFiles { f -> f.name.endsWith(".tmp") }!!.size

    private fun bytes(text: String, charset: Charset) = ByteArrayInputStream(text.toByteArray(charset))

    private val accented = "#EXTM3U\n#EXTINF:-1 group-title=\"Notícias\",Globo Ação\nhttp://h.example/a\n"

    // ---- codificação ---------------------------------------------------------

    @Test fun utf8StaysUtf8() {
        val r = repo()
        val p = r.importFromFile(bytes(accented, Charsets.UTF_8), "lista.m3u")
        val ch = r.loadChannels(p.id).channels.single()
        assertEquals("Globo Ação", ch.name)
        assertEquals("Notícias", ch.group)
        assertEquals(0, tempLeftovers())
    }

    @Test fun latin1ListIsConvertedSoAccentsSurvive() {
        val r = repo()
        val p = r.importFromFile(bytes(accented, Charsets.ISO_8859_1), "antiga.m3u")
        val ch = r.loadChannels(p.id).channels.single()
        assertEquals("Globo Ação", ch.name)
        assertEquals("Notícias", ch.group)
        assertEquals(0, tempLeftovers())
    }

    @Test fun utf16WithBomIsConverted() {
        val body = accented.toByteArray(Charsets.UTF_16LE)
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        val r = repo()
        val p = r.importFromFile(ByteArrayInputStream(withBom), "notepad.m3u")
        assertEquals("Globo Ação", r.loadChannels(p.id).channels.single().name)
        assertEquals(0, tempLeftovers())
    }

    // ---- conteúdo que não é uma lista ---------------------------------------

    @Test fun htmlPageServedWithStatus200IsRejectedAsEmpty() {
        val html = "<html><body>Faça login para continuar</body></html>"
        val r = repo(ContentFetcher { ByteArrayInputStream(html.toByteArray()) })
        assertThrows(PlaylistError.Empty::class.java) { r.importFromUrl("http://h.example/lista") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    @Test fun singleHlsStreamIsRejectedWithAClearError() {
        val hls = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts\n#EXTINF:10.0,\nseg2.ts\n"
        val r = repo(ContentFetcher { ByteArrayInputStream(hls.toByteArray()) })
        assertThrows(PlaylistError.NotAChannelList::class.java) { r.importFromUrl("http://h.example/live/index.m3u8") }
        assertThrows(PlaylistError.NotAChannelList::class.java) { r.importFromText(hls) }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    // ---- limites -------------------------------------------------------------

    @Test fun tooManyChannelsIsRejectedAndNothingIsSaved() {
        val text = "#EXTM3U\n" + (1..6).joinToString("") { "#EXTINF:-1,C$it\nhttp://h.example/$it\n" }
        val r = repo(maxChannels = 5)
        val e = assertThrows(PlaylistError.TooManyChannels::class.java) { r.importFromText(text) }
        assertEquals(5, e.maxChannels)
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    @Test fun exactlyTheLimitIsAccepted() {
        val text = "#EXTM3U\n" + (1..5).joinToString("") { "#EXTINF:-1,C$it\nhttp://h.example/$it\n" }
        assertEquals(5, repo(maxChannels = 5).importFromText(text).channelCount)
    }

    /** Servidor "conta-gotas": cada leitura tem timeout próprio, mas o download inteiro tem limite. */
    private inner class SlowStream(private var remaining: Int) : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            now += 600 // cada leitura "demora" 600 ms
            if (remaining <= 0) return -1
            val n = minOf(len, 10, remaining)
            for (i in 0 until n) b[off + i] = 'a'.code.toByte()
            remaining -= n
            return n
        }
    }

    @Test fun veryLongDownloadTimesOutAndLeavesNothingBehind() {
        val r = repo(ContentFetcher { SlowStream(10_000) }, maxLoadMs = 1_000)
        assertThrows(PlaylistError.Timeout::class.java) { r.importFromUrl("http://h.example/lenta") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    // ---- falhas de rede ------------------------------------------------------

    @Test fun networkFailureHidesTheUrlFromTheMessage() {
        val r = repo(ContentFetcher { url -> throw IOException("falhou em $url") })
        val e = assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("http://h.example/segredo-token/lista.m3u") }
        assertFalse(e.message!!.contains("segredo"))
        assertFalse(e.message!!.contains("h.example"))
    }

    @Test fun connectionDroppedMidDownloadLeavesNothingBehind() {
        val broken = object : InputStream() {
            private var calls = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ == 0) {
                    val first = "#EXTM3U\n#EXTINF:-1,A\nhttp://h.example/1\n".toByteArray()
                    first.copyInto(b, off)
                    return first.size
                }
                throw IOException("Connection reset")
            }
        }
        val r = repo(ContentFetcher { broken })
        assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("http://h.example/cai") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    // ---- URLs inválidas ------------------------------------------------------

    @Test fun invalidUrlsAreRejectedBeforeAnyDownload() {
        var opened = 0
        val r = repo(ContentFetcher { opened++; throw IOException("não deveria abrir") })
        val bad = listOf(
            "", "   ", "lista.m3u", "ftp://h.example/a.m3u", "file:///sdcard/a.m3u", "javascript:alert(1)",
            "//h.example/a.m3u", "http://", "http:///a.m3u", "http://host com espaco/a.m3u", "https:/h.example/a",
        )
        for (url in bad) {
            assertThrows("deveria rejeitar: $url", PlaylistError.InvalidUrl::class.java) { r.importFromUrl(url) }
        }
        assertEquals(0, opened)
    }

    // ---- fluxo completo na JVM (importar → carregar → buscar) -----------------

    @Test fun importLoadAndSearchEndToEnd() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="" tvg-name="BR| CANAL 1 HD" group-title="BR | ABERTOS",BR| CANAL 1 HD
            http://painel.example:8080/live/usuario/senha/101.ts
            #EXTINF:-1 tvg-id="" tvg-name="BR| CANAL 2 HD" group-title="BR | ABERTOS",BR| CANAL 2 HD
            http://painel.example:8080/live/usuario/senha/102.ts
            #EXTINF:-1 tvg-id="" tvg-name="UK| NEWS" group-title="UK | NEWS",UK| NEWS
            http://painel.example:8080/live/usuario/senha/201.ts
        """.trimIndent()
        val r = repo()
        val p = r.importFromText(text, "Teste")
        val catalog = ChannelCatalog(repo().loadChannels(p.id).channels) // outro repositório = reabrir o app
        assertEquals(3, catalog.channels.size)
        assertEquals(listOf("BR| CANAL 2 HD"), catalog.query(text = "canal 2").map { it.name })
        assertEquals(2, catalog.query(ChannelTab.Group("BR | ABERTOS")).size)
    }
}
