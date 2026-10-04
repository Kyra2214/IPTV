package com.kyra.iptv.data

import com.kyra.iptv.data.model.Channel
import java.text.Normalizer
import java.util.Locale

sealed class ChannelTab {
    object All : ChannelTab()
    object Favorites : ChannelTab()
    object Recent : ChannelTab()
    data class Group(val name: String) : ChannelTab()
}

enum class ChannelSort { PLAYLIST_ORDER, NAME }

/** Texto estável para salvar a aba atual (ex.: em `onSaveInstanceState`). */
fun ChannelTab.toKey(): String = when (this) {
    ChannelTab.All -> "all"
    ChannelTab.Favorites -> "fav"
    ChannelTab.Recent -> "recent"
    is ChannelTab.Group -> "group:$name"
}

/** Inverso de [toKey]; chave ausente ou desconhecida volta para [ChannelTab.All]. */
fun channelTabFromKey(key: String?): ChannelTab = when {
    key == null -> ChannelTab.All
    key == "fav" -> ChannelTab.Favorites
    key == "recent" -> ChannelTab.Recent
    key.startsWith("group:") -> ChannelTab.Group(key.removePrefix("group:"))
    else -> ChannelTab.All
}

data class GroupInfo(val name: String, val count: Int)

/**
 * Índice em memória dos canais de uma playlist para listar, filtrar por aba/grupo, buscar e ordenar.
 * Sem dependência de Android. Os nomes são normalizados uma única vez (sem acento, minúsculos),
 * então cada digitação na busca só faz comparações de string, mesmo com dezenas de milhares de canais.
 *
 * Busca: por nome, sem diferenciar maiúsculas/acentos; várias palavras = todas precisam aparecer.
 */
class ChannelCatalog(val channels: List<Channel>) {

    private val keys: Array<String> = Array(channels.size) { normalize(channels[it].name) }
    private val indexById: HashMap<String, Int> = HashMap<String, Int>(channels.size * 2).also { m ->
        channels.forEachIndexed { i, c -> m.putIfAbsent(c.id, i) }
    }

    /** Grupos na ordem em que aparecem na playlist; `Ungrouped` sempre por último. */
    val groups: List<GroupInfo> = run {
        val counts = LinkedHashMap<String, Int>()
        for (c in channels) counts[c.group] = (counts[c.group] ?: 0) + 1
        val list = counts.map { GroupInfo(it.key, it.value) }
        list.filter { it.name != Channel.UNGROUPED } + list.filter { it.name == Channel.UNGROUPED }
    }

    /**
     * @param favorites ids favoritos; @param recent ids do mais recente ao mais antigo.
     * A aba Recentes mantém a ordem do histórico (ignora [sort]).
     */
    fun query(
        tab: ChannelTab = ChannelTab.All,
        text: String = "",
        sort: ChannelSort = ChannelSort.PLAYLIST_ORDER,
        favorites: Set<String> = emptySet(),
        recent: List<String> = emptyList(),
    ): List<Channel> {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() }

        fun matches(i: Int): Boolean {
            val key = keys[i]
            for (t in tokens) if (!key.contains(t)) return false
            return true
        }

        if (tab is ChannelTab.Recent) {
            return recent.mapNotNull { indexById[it] }.distinct().filter { matches(it) }.map { channels[it] }
        }

        val picked = ArrayList<Int>()
        for (i in channels.indices) {
            val c = channels[i]
            val inTab = when (tab) {
                ChannelTab.All -> true
                ChannelTab.Favorites -> c.id in favorites
                is ChannelTab.Group -> c.group == tab.name
                ChannelTab.Recent -> false
            }
            if (inTab && matches(i)) picked += i
        }
        if (sort == ChannelSort.NAME) picked.sortBy { keys[it] } // estável: empates mantêm a ordem da playlist
        return picked.map { channels[it] }
    }

    companion object {
        private val MARKS = Regex("\\p{M}+")

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).trim()
    }
}
