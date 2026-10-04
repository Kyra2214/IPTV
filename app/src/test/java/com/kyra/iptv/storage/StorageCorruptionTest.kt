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

/** Fase 7: índice corrompido, truncado ou com ids hostis não pode derrubar o app nem apagar o resto. */
class StorageCorruptionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val valid = Playlist("id-1", "Minha", "http://h.example/x", SourceType.URL, 5L, 3)

    @Test fun corruptedLinesAreIgnoredAndValidOnesSurvive() {
        val root = tmp.newFolder("a")
        val s = LocalStorage(root)
        s.writePlaylists(listOf(valid))
        File(root, "playlists.tsv").appendText(
            "lixo sem tabs\n" +
                "../evil\tN\thttp://x\tURL\t1\t1\n" + // id com path traversal
                "id-2\tN\tsrc\tINEXISTENTE\t1\t1\n" + // tipo desconhecido
                "id-3\tN\tsrc\tURL\tabc\t1\n" + // número inválido
                "id-4\tN\tsrc\tURL\t1\n" + // colunas faltando
                "\u0000\u0001\n"
        )
        assertEquals(listOf(valid), s.readPlaylists())
    }

    @Test fun emptyOrBinaryIndexReadsAsNoPlaylists() {
        val root = tmp.newFolder("b")
        val s = LocalStorage(root)
        File(root, "playlists.tsv").writeBytes(ByteArray(0))
        assertTrue(s.readPlaylists().isEmpty())
        File(root, "playlists.tsv").writeBytes(ByteArray(2_000) { (it * 31).toByte() })
        assertTrue(s.readPlaylists().isEmpty()) // não lança
    }

    @Test fun leftoverTempFilesAreRemovedOnStartup() {
        val root = tmp.newFolder("c")
        LocalStorage(root) // cria content/
        val leftover = File(root, "content/import123.tmp").also { it.writeText("meio baixado") }
        val kept = File(root, "content/id-1.m3u").also { it.writeText("#EXTM3U") }
        LocalStorage(root) // "reabrir o app"
        assertTrue(!leftover.exists())
        assertTrue(kept.exists())
    }

    @Test fun contentFileRejectsPathTraversalIds() {
        val s = LocalStorage(tmp.newFolder("d"))
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("../x") }
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("a/b") }
        assertThrows(IllegalArgumentException::class.java) { s.contentFile("") }
    }

    @Test fun missingContentFailsWithIoExceptionNotACrash() {
        val s = LocalStorage(tmp.newFolder("e"))
        assertThrows(java.io.IOException::class.java) { s.openContent("id-inexistente") }
    }
}
