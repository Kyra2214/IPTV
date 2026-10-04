package com.kyra.iptv.data.repository

import com.kyra.iptv.storage.LocalStorage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Etapa 5: análise e fonte de canais a partir da lista salva. */
class PlaylistRepositoryGarimpoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = PlaylistRepository(
        LocalStorage(tmp.newFolder("data")),
        ContentFetcher { throw IOException("sem rede neste teste") },
    )

    private val text = """
        #EXTM3U
        #EXTINF:-1 group-title="A",Um
        http://h.example/1
        #EXTINF:-1 group-title="B",Dois
        http://h.example/2
        #EXTINF:-1 group-title="B",Repetido
        http://h.example/2
    """.trimIndent()

    @Test fun analyzeReadsTheSavedList() {
        val r = repo()
        val p = r.importFromText(text)
        val a = r.analyze(p.id)
        assertEquals(3, a.totalEntries)
        assertEquals(2, a.uniqueUrls)
        assertEquals(1, a.duplicates)
        assertEquals(2, a.groups)
    }

    @Test fun channelSourceDeliversTheSavedChannels() {
        val r = repo()
        val p = r.importFromText(text)
        val source = r.openChannelSource(p.id)
        val names = ArrayList<String>()
        while (true) names += source.next()?.name ?: break
        source.close()
        assertEquals(listOf("Um", "Dois"), names)
    }

    @Test fun channelSourceResolvesRelativeUrlsAgainstTheListUrl() {
        val rel = "#EXTM3U\n#EXTINF:-1,Rel\nlive/1.m3u8\n"
        val r = PlaylistRepository(
            LocalStorage(tmp.newFolder("data2")),
            ContentFetcher { rel.byteInputStream() },
        )
        val p = r.importFromUrl("http://host.example/lists/a.m3u")
        val source = r.openChannelSource(p.id)
        assertEquals("http://host.example/lists/live/1.m3u8", source.next()?.streamUrl)
        assertNull(source.next())
    }

    @Test fun unknownListFails() {
        val r = repo()
        assertThrows(PlaylistError.NotFound::class.java) { r.analyze("00000000-0000-0000-0000-000000000000") }
        assertThrows(PlaylistError.NotFound::class.java) { r.openChannelSource("00000000-0000-0000-0000-000000000000") }
    }

    // ---- salvar funcionais --------------------------------------------------

    private fun channelsOf(r: PlaylistRepository, id: String) = r.loadChannels(id).channels

    @Test fun saveTestedCreatesANewListWithTheGivenChannels() {
        val r = repo()
        val original = r.importFromText(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",A\n#EXTVLCOPT:http-user-agent=UA\nhttp://h.example/a\n" +
                "#EXTINF:-1,B\nhttp://h.example/b\n"
        )
        val working = channelsOf(r, original.id).filter { it.name == "A" }
        val saved = r.saveTested(working, "Só A")
        assertEquals("Só A", saved.name)
        assertEquals(com.kyra.iptv.data.model.SourceType.TESTED, saved.sourceType)
        assertEquals(1, saved.channelCount)
        assertEquals(working, channelsOf(r, saved.id))
        assertEquals(2, r.getAll().size)
        assertEquals(2, channelsOf(r, original.id).size) // a original não muda
    }

    @Test fun saveTestedUsesADefaultNameAndFailsOnEmpty() {
        val r = repo()
        val p = r.importFromText(text)
        val saved = r.saveTested(channelsOf(r, p.id), "   ")
        assertEquals("Lista testada", saved.name)
        assertThrows(PlaylistError.Empty::class.java) { r.saveTested(emptyList()) }
        assertEquals(2, r.getAll().size)
    }

    @Test fun saveTestedLeavesNoTempFiles() {
        val r = repo()
        val p = r.importFromText(text)
        r.saveTested(channelsOf(r, p.id), "X")
        val leftovers = java.io.File(tmp.root, "data/content").listFiles { f -> f.name.endsWith(".tmp") }!!
        assertEquals(0, leftovers.size)
    }

    // ---- juntar listas ------------------------------------------------------

    private fun list(vararg entries: String) =
        "#EXTM3U\n" + entries.joinToString("") { "#EXTINF:-1,${it.substringAfterLast('/')}\n$it\n" }

    @Test fun mergeJoinsListsInOrderAndDropsRepeatedUrls() {
        val r = repo()
        val a = r.importFromText(list("http://h.example/1", "http://h.example/2"), "A")
        val b = r.importFromText(list("http://h.example/2", "http://h.example/3"), "B")
        val c = r.importFromText(list("http://h.example/1", "http://h.example/4"), "C")
        val result = r.merge(listOf(a.id, b.id, c.id), "Tudo")
        assertEquals("Tudo", result.playlist.name)
        assertEquals(com.kyra.iptv.data.model.SourceType.MERGED, result.playlist.sourceType)
        assertEquals(4, result.merged)
        assertEquals(2, result.duplicates)
        assertEquals(4, result.playlist.channelCount)
        assertEquals(
            listOf("http://h.example/1", "http://h.example/2", "http://h.example/3", "http://h.example/4"),
            channelsOf(r, result.playlist.id).map { it.streamUrl },
        )
        assertEquals(4, r.getAll().size) // as originais continuam
        assertEquals(2, channelsOf(r, a.id).size)
    }

    @Test fun mergeKeepsAttributesAndHeaders() {
        val r = repo()
        val a = r.importFromText(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\" group-title=\"G\",Um\n#EXTVLCOPT:http-referrer=http://r.example/\nhttp://h.example/1\n"
        )
        val b = r.importFromText(list("http://h.example/2"))
        val merged = r.merge(listOf(a.id, b.id)).playlist
        val first = channelsOf(r, merged.id).first()
        assertEquals("x", first.tvgId)
        assertEquals("G", first.group)
        assertEquals(mapOf("Referer" to "http://r.example/"), first.headers)
        assertEquals("Listas juntas", merged.name)
    }

    @Test fun mergeResolvesRelativeUrlsOfEachUrlListAgainstItsOwnBase() {
        val rel = "#EXTM3U\n#EXTINF:-1,Rel\nlive/1.m3u8\n"
        val r = PlaylistRepository(LocalStorage(tmp.newFolder("rel")), ContentFetcher { rel.byteInputStream() })
        val a = r.importFromUrl("http://one.example/lists/a.m3u")
        val b = r.importFromUrl("http://two.example/b.m3u")
        val urls = channelsOf(r, r.merge(listOf(a.id, b.id)).playlist.id).map { it.streamUrl }
        assertEquals(listOf("http://one.example/lists/live/1.m3u8", "http://two.example/live/1.m3u8"), urls)
    }

    @Test fun mergeNeedsTwoDistinctExistingLists() {
        val r = repo()
        val a = r.importFromText(text)
        assertThrows(PlaylistError.NotEnoughLists::class.java) { r.merge(listOf(a.id)) }
        assertThrows(PlaylistError.NotEnoughLists::class.java) { r.merge(listOf(a.id, a.id)) }
        assertThrows(PlaylistError.NotFound::class.java) { r.merge(listOf(a.id, "00000000-0000-0000-0000-000000000000")) }
        assertEquals(1, r.getAll().size)
    }

    @Test fun mergeRespectsTheChannelLimitAndLeavesNothingBehind() {
        val root = tmp.newFolder("lim")
        val big = PlaylistRepository(LocalStorage(root), ContentFetcher { throw IOException("sem rede") })
        val a = big.importFromText(list("http://h.example/1", "http://h.example/2"))
        val b = big.importFromText(list("http://h.example/3"))
        val small = PlaylistRepository(LocalStorage(root), ContentFetcher { throw IOException("sem rede") }, maxChannels = 2)
        assertThrows(PlaylistError.TooManyChannels::class.java) { small.merge(listOf(a.id, b.id)) }
        assertEquals(2, small.getAll().size)
        assertEquals(0, java.io.File(root, "content").listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }

    @Test fun mergedListCanBeAnalyzedAndTested() {
        val r = repo()
        val a = r.importFromText(list("http://h.example/1", "http://h.example/2"))
        val b = r.importFromText(list("http://h.example/2", "http://h.example/3"))
        val merged = r.merge(listOf(a.id, b.id)).playlist
        val analysis = r.analyze(merged.id)
        assertEquals(3, analysis.uniqueUrls)
        assertEquals(0, analysis.duplicates)
    }
}
