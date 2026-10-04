package com.kyra.iptv.storage

import com.kyra.iptv.data.model.Playlist
import com.kyra.iptv.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun p(id: String, name: String = "N", source: String = "") =
        Playlist(id, name, source, SourceType.PASTED, 123L, 7)

    @Test fun emptyStorageHasNoPlaylists() {
        assertTrue(LocalStorage(tmp.newFolder("a")).readPlaylists().isEmpty())
    }

    @Test fun roundTripWithSpecialCharacters() {
        val dir = tmp.newFolder("b")
        val weird = p("id-1", name = "Tab\there\nNova linha \\ barra \r fim", source = "http://x/?a=1&b=2")
        LocalStorage(dir).writePlaylists(listOf(weird, p("id-2", name = "Normal")))
        assertEquals(listOf(weird, p("id-2", name = "Normal")), LocalStorage(dir).readPlaylists())
    }

    @Test fun corruptLinesAreIgnored() {
        val dir = tmp.newFolder("c")
        val s = LocalStorage(dir)
        s.writePlaylists(listOf(p("ok-1")))
        File(dir, "playlists.tsv").appendText("lixo sem tabs\nid-x\tn\ts\tTIPO_INVALIDO\t1\t1\nbad id!\tn\ts\tURL\t1\t1\n")
        assertEquals(listOf(p("ok-1")), s.readPlaylists())
    }

    @Test fun invalidIdsAreRejected() {
        val s = LocalStorage(tmp.newFolder("d"))
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("../fora") }
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("a/b") }
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("") }
    }

    @Test fun commitReplacesExistingContent() {
        val s = LocalStorage(tmp.newFolder("e"))
        val t1 = s.newTempFile().also { it.writeText("um") }
        s.commitContent(t1, "abc")
        val t2 = s.newTempFile().also { it.writeText("dois") }
        s.commitContent(t2, "abc")
        assertEquals("dois", s.openContent("abc").use { it.readText() })
    }

    @Test fun staleTempFilesAreCleanedOnStartup() {
        val dir = tmp.newFolder("f")
        val s = LocalStorage(dir)
        val stale = s.newTempFile()
        assertTrue(stale.exists())
        LocalStorage(dir)
        assertTrue(!stale.exists())
    }
}
