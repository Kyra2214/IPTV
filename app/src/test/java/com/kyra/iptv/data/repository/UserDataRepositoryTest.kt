package com.kyra.iptv.data.repository

import com.kyra.iptv.storage.LocalStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserDataRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun favoritesPersistAcrossRestart() {
        val dir = tmp.newFolder("a")
        val f = FavoritesRepository(LocalStorage(dir))
        assertTrue(f.getAll().isEmpty())
        f.setFavorite("abc", true)
        f.setFavorite("def", true)
        f.setFavorite("abc", true) // repetido: sem efeito
        f.setFavorite("def", false)

        val reopened = FavoritesRepository(LocalStorage(dir))
        assertEquals(setOf("abc"), reopened.getAll())
    }

    @Test fun favoritesSnapshotIsACopy() {
        val f = FavoritesRepository(LocalStorage(tmp.newFolder("b")))
        f.setFavorite("x", true)
        val snap = f.getAll()
        f.setFavorite("y", true)
        assertEquals(setOf("x"), snap)
    }

    @Test fun historyIsMostRecentFirstWithoutDuplicates() {
        val h = HistoryRepository(LocalStorage(tmp.newFolder("c")))
        h.record("a"); h.record("b"); h.record("c"); h.record("a")
        assertEquals(listOf("a", "c", "b"), h.getAll())
    }

    @Test fun historyIsLimitedAndPersistent() {
        val dir = tmp.newFolder("d")
        val h = HistoryRepository(LocalStorage(dir), maxEntries = 3)
        for (i in 1..5) h.record("id$i")
        assertEquals(listOf("id5", "id4", "id3"), h.getAll())
        assertEquals(listOf("id5", "id4", "id3"), HistoryRepository(LocalStorage(dir), maxEntries = 3).getAll())
    }

    @Test fun storageRejectsUnsafeFileNames() {
        val s = LocalStorage(tmp.newFolder("f"))
        assertThrows(IllegalArgumentException::class.java) { s.readLines("../x.txt") }
        assertThrows(IllegalArgumentException::class.java) { s.writeLines("playlists.tsv", emptyList()) }
    }
}
