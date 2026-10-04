package com.kyra.iptv.data.repository

import com.kyra.iptv.data.model.SourceType
import com.kyra.iptv.storage.LocalStorage
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress

class PlaylistRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var root: File
    private var now = 1_000L
    private val routes = HashMap<String, () -> Triple<Int, String, Map<String, String>>>()

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        root = tmp.newFolder("data")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val route = routes[ex.requestURI.path]
            if (route == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val (code, body, headers) = route()
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                if (code in 300..399) {
                    ex.sendResponseHeaders(code, -1)
                } else {
                    val bytes = body.toByteArray()
                    ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
                }
            }
            ex.close()
        }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private fun repo(maxBytes: Long = PlaylistRepository.DEFAULT_MAX_BYTES) =
        PlaylistRepository(LocalStorage(root), clock = { now }, maxBytes = maxBytes)

    private fun serve(path: String, body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        routes[path] = { Triple(code, body, headers) }
    }

    private fun m3u(vararg names: String) =
        "#EXTM3U\n" + names.joinToString("") { "#EXTINF:-1 group-title=\"G\",$it\nhttp://h/$it\n" }

    private fun tempLeftovers() = File(root, "content").listFiles { f -> f.name.endsWith(".tmp") }!!.size

    // ---- colar / arquivo ----------------------------------------------------

    @Test fun pasteSavesAndSurvivesRestart() {
        val saved = repo().importFromText(m3u("A", "B", "C"), "Minha Lista")
        assertEquals("Minha Lista", saved.name)
        assertEquals(SourceType.PASTED, saved.sourceType)
        assertEquals(3, saved.channelCount)

        val reopened = repo() // novo repositório, mesmo diretório = "fechar e abrir o app"
        assertEquals(listOf(saved), reopened.getAll())
        assertEquals(listOf("A", "B", "C"), reopened.loadChannels(saved.id).channels.map { it.name })
    }

    @Test fun pasteDefaultName() {
        assertEquals("Lista colada", repo().importFromText(m3u("A"), "  ").name)
    }

    @Test fun fileImportUsesFileNameAndClosesStream() {
        var closed = false
        val input = object : ByteArrayInputStream(m3u("A", "B").toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val p = repo().importFromFile(input, "canais_br.m3u8")
        assertEquals("canais_br", p.name)
        assertEquals("canais_br.m3u8", p.source)
        assertEquals(SourceType.FILE, p.sourceType)
        assertTrue(closed)
    }

    @Test fun emptyOrInvalidContentIsRejectedAndNothingIsSaved() {
        val r = repo()
        assertThrows(PlaylistError.Empty::class.java) { r.importFromText("", "x") }
        assertThrows(PlaylistError.Empty::class.java) { r.importFromText("<html>nada</html>", "x") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
        assertEquals(0, File(root, "content").listFiles { f -> f.name.endsWith(".m3u") }!!.size)
    }

    @Test fun oversizedContentIsRejected() {
        val r = repo(maxBytes = 200)
        assertThrows(PlaylistError.TooLarge::class.java) { r.importFromText(m3u(*Array(50) { "Canal$it" }), "x") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    @Test fun largePlaylistImportAndLoad() {
        val text = buildString {
            append("#EXTM3U\n")
            for (i in 0 until 50_000) append("#EXTINF:-1 group-title=\"G${i % 10}\",C$i\nhttp://h/$i\n")
        }
        val r = repo()
        val p = r.importFromText(text, "Grande")
        assertEquals(50_000, p.channelCount)
        assertEquals(50_000, r.loadChannels(p.id).channels.size)
    }

    // ---- URL ----------------------------------------------------------------

    @Test fun urlImportResolvesRelativeUrlsAgainstSource() {
        serve("/lists/main.m3u", "#EXTM3U\n#EXTINF:-1,A\nstream/a.m3u8\n#EXTINF:-1,B\n/live/b.m3u8\n")
        val r = repo()
        val p = r.importFromUrl("$base/lists/main.m3u")
        assertEquals(SourceType.URL, p.sourceType)
        assertEquals("$base/lists/main.m3u", p.source)
        assertEquals("127.0.0.1", p.name)
        assertEquals(
            listOf("$base/lists/stream/a.m3u8", "$base/live/b.m3u8"),
            repo().loadChannels(p.id).channels.map { it.streamUrl } // após "reabrir"
        )
    }

    @Test fun urlImportFollowsRedirects() {
        serve("/old", "", 302, mapOf("Location" to "/new"))
        serve("/new", m3u("A"))
        assertEquals(1, repo().importFromUrl("$base/old", "R").channelCount)
    }

    @Test fun redirectToUnsupportedSchemeFails() {
        serve("/bad", "", 302, mapOf("Location" to "file:///etc/passwd"))
        val r = repo()
        assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("$base/bad") }
        assertTrue(r.getAll().isEmpty())
    }

    @Test fun redirectLoopFails() {
        serve("/loop", "", 302, mapOf("Location" to "/loop"))
        assertThrows(PlaylistError.Network::class.java) { repo().importFromUrl("$base/loop") }
    }

    @Test fun httpErrorsAndConnectionFailuresAreNetworkErrors() {
        serve("/boom", "x", 500)
        val r = repo()
        val e1 = assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("$base/boom") }
        assertFalse(e1.message!!.contains(base)) // mensagens não vazam a URL
        assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("$base/inexistente") } // 404
        server.stop(0)
        assertThrows(PlaylistError.Network::class.java) { r.importFromUrl("http://127.0.0.1:${server.address.port}/x") }
        assertTrue(r.getAll().isEmpty())
        assertEquals(0, tempLeftovers())
    }

    @Test fun invalidUrlsAreRejectedBeforeAnyRequest() {
        val r = repo()
        for (bad in listOf("", "abc", "ftp://h/x.m3u", "file:///x.m3u", "http://", "javascript:alert(1)")) {
            assertThrows("'$bad'", PlaylistError.InvalidUrl::class.java) { r.importFromUrl(bad) }
        }
    }

    // ---- atualizar ----------------------------------------------------------

    @Test fun refreshReplacesContentAndUpdatesMetadata() {
        serve("/l.m3u", m3u("A"))
        val r = repo()
        val p = r.importFromUrl("$base/l.m3u", "Fonte")
        now = 5_000L
        serve("/l.m3u", m3u("A", "B", "C"))
        val updated = r.refresh(p.id)
        assertEquals(3, updated.channelCount)
        assertEquals(5_000L, updated.updatedAt)
        assertEquals("Fonte", updated.name)
        assertEquals(listOf("A", "B", "C"), repo().loadChannels(p.id).channels.map { it.name })
        assertEquals(updated, repo().get(p.id))
    }

    @Test fun failedRefreshKeepsPreviousList() {
        serve("/l.m3u", m3u("A", "B"))
        val r = repo()
        val p = r.importFromUrl("$base/l.m3u")

        serve("/l.m3u", "erro", 500)
        assertThrows(PlaylistError.Network::class.java) { r.refresh(p.id) }
        serve("/l.m3u", "")
        assertThrows(PlaylistError.Empty::class.java) { r.refresh(p.id) }

        assertEquals(p, r.get(p.id))
        assertEquals(listOf("A", "B"), r.loadChannels(p.id).channels.map { it.name })
        assertEquals(0, tempLeftovers())
    }

    @Test fun refreshOnlyForUrlPlaylists() {
        val r = repo()
        val p = r.importFromText(m3u("A"), "x")
        assertThrows(PlaylistError.NotRefreshable::class.java) { r.refresh(p.id) }
        assertThrows(PlaylistError.NotFound::class.java) { r.refresh("nao-existe") }
    }

    // ---- renomear / excluir -------------------------------------------------

    @Test fun renamePersistsAndRejectsBlank() {
        val r = repo()
        val p = r.importFromText(m3u("A"), "Antigo")
        assertEquals("Novo nome", r.rename(p.id, "  Novo nome  ").name)
        assertEquals("Novo nome", repo().get(p.id)!!.name)
        assertThrows(PlaylistError.InvalidName::class.java) { r.rename(p.id, "   ") }
        assertThrows(PlaylistError.NotFound::class.java) { r.rename("nao-existe", "x") }
        assertEquals("Novo nome", r.get(p.id)!!.name)
    }

    @Test fun deleteRemovesIndexEntryAndContentOnly() {
        val r = repo()
        val a = r.importFromText(m3u("A"), "A")
        val b = r.importFromText(m3u("B"), "B")
        r.delete(a.id)
        assertEquals(listOf(b.id), repo().getAll().map { it.id })
        assertFalse(File(root, "content/${a.id}.m3u").exists())
        assertTrue(File(root, "content/${b.id}.m3u").exists())
        assertThrows(PlaylistError.NotFound::class.java) { r.loadChannels(a.id) }
        assertThrows(PlaylistError.NotFound::class.java) { r.delete(a.id) }
        assertNull(r.get(a.id))
    }

    @Test fun playlistsAreIndependentAndKeepInsertionOrder() {
        val r = repo()
        val a = r.importFromText(m3u("A1", "A2"), "A")
        val b = r.importFromText(m3u("B1"), "B")
        assertEquals(listOf(a.id, b.id), r.getAll().map { it.id })
        assertEquals(listOf("A1", "A2"), r.loadChannels(a.id).channels.map { it.name })
        assertEquals(listOf("B1"), r.loadChannels(b.id).channels.map { it.name })
    }
}
