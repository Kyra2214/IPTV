package com.kyra.iptv.data

import com.kyra.iptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogTest {
    private fun ch(id: String, name: String, group: String = Channel.UNGROUPED) =
        Channel(id = id, name = name, streamUrl = "http://h/$id", group = group)

    private val list = listOf(
        ch("1", "Globo Esporte", "Esportes"),
        ch("2", "Cinema Ação", "Filmes"),
        ch("3", "SporTV", "Esportes"),
        ch("4", "Sem Grupo"),
        ch("5", "Canal Notícias", "Jornalismo"),
        ch("6", "Zeta Filmes", "Filmes"),
    )
    private val catalog = ChannelCatalog(list)

    private fun names(r: List<Channel>) = r.map { it.name }

    @Test fun allKeepsPlaylistOrder() {
        assertEquals(list, catalog.query())
    }

    @Test fun groupsInOrderWithUngroupedLast() {
        assertEquals(
            listOf(GroupInfo("Esportes", 2), GroupInfo("Filmes", 2), GroupInfo("Jornalismo", 1), GroupInfo(Channel.UNGROUPED, 1)),
            catalog.groups
        )
    }

    @Test fun groupTab() {
        assertEquals(listOf("Cinema Ação", "Zeta Filmes"), names(catalog.query(ChannelTab.Group("Filmes"))))
        assertTrue(catalog.query(ChannelTab.Group("Inexistente")).isEmpty())
    }

    @Test fun favoritesTabKeepsPlaylistOrder() {
        assertEquals(listOf("Globo Esporte", "Zeta Filmes"), names(catalog.query(ChannelTab.Favorites, favorites = setOf("6", "1"))))
        assertTrue(catalog.query(ChannelTab.Favorites).isEmpty())
    }

    @Test fun recentTabFollowsHistoryOrderIgnoresUnknownIdsAndSort() {
        val r = catalog.query(ChannelTab.Recent, sort = ChannelSort.NAME, recent = listOf("5", "999", "1", "5"))
        assertEquals(listOf("Canal Notícias", "Globo Esporte"), names(r))
    }

    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals(listOf("Cinema Ação"), names(catalog.query(text = "acao")))
        assertEquals(listOf("Cinema Ação"), names(catalog.query(text = "AÇÃO")))
        assertEquals(listOf("Canal Notícias"), names(catalog.query(text = "noticias")))
        assertEquals(listOf("SporTV"), names(catalog.query(text = "sportv")))
    }

    @Test fun searchWithSeveralWordsRequiresAll() {
        assertEquals(listOf("Globo Esporte"), names(catalog.query(text = "esporte globo")))
        assertTrue(catalog.query(text = "globo cinema").isEmpty())
    }

    @Test fun searchCombinesWithTabs() {
        assertEquals(listOf("Zeta Filmes"), names(catalog.query(ChannelTab.Group("Filmes"), text = "zeta")))
        assertEquals(listOf("SporTV"), names(catalog.query(ChannelTab.Group("Esportes"), text = "tv")))
        assertEquals(listOf("Globo Esporte"), names(catalog.query(ChannelTab.Favorites, text = "globo", favorites = setOf("1", "2"))))
    }

    @Test fun blankSearchReturnsEverything() {
        assertEquals(list.size, catalog.query(text = "   ").size)
    }

    @Test fun sortByNameIsAccentInsensitiveAndStable() {
        val c = ChannelCatalog(listOf(ch("1", "Édito"), ch("2", "beta"), ch("3", "Alfa"), ch("4", "alfa")))
        assertEquals(listOf("Alfa", "alfa", "beta", "Édito"), names(c.query(sort = ChannelSort.NAME)))
    }

    @Test fun emptyCatalog() {
        val c = ChannelCatalog(emptyList())
        assertTrue(c.query().isEmpty() && c.groups.isEmpty())
    }

    @Test fun largeCatalogSearchIsFast() {
        val big = ChannelCatalog((0 until 100_000).map { ch("$it", "Canal Número $it", "G${it % 40}") })
        val start = System.nanoTime()
        repeat(20) { big.query(text = "numero 9999") }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(19, big.query(text = "numero 9999").size) // 9999, x9999 (19999..99999) e 99990..99998
        assertTrue("20 buscas em 100k canais levaram ${ms}ms", ms < 3_000)
    }

    @Test fun tabKeysRoundTripForSavedState() {
        val tabs = listOf(ChannelTab.All, ChannelTab.Favorites, ChannelTab.Recent, ChannelTab.Group("Esportes"), ChannelTab.Group("A:b | C"))
        for (t in tabs) assertEquals(t.toKey(), channelTabFromKey(t.toKey()).toKey())
        assertEquals(ChannelTab.Group("A:b | C"), channelTabFromKey(ChannelTab.Group("A:b | C").toKey()))
    }

    @Test fun missingOrUnknownTabKeyFallsBackToAll() {
        assertEquals(ChannelTab.All, channelTabFromKey(null))
        assertEquals(ChannelTab.All, channelTabFromKey(""))
        assertEquals(ChannelTab.All, channelTabFromKey("qualquer-coisa"))
    }
}
