package com.kyra.iptv.data.repository

import com.kyra.iptv.storage.LocalStorage

/**
 * Favoritos por id estável de canal ([com.kyra.iptv.data.parser.ChannelId]), globais:
 * o mesmo canal (mesma URL) continua favorito se aparecer em outra lista ou após atualizar.
 * Bloqueante (disco): chamar fora da thread principal.
 */
class FavoritesRepository(private val storage: LocalStorage) {
    private val lock = Any()
    private var ids: LinkedHashSet<String>? = null

    private fun loaded(): LinkedHashSet<String> =
        ids ?: LinkedHashSet(storage.readLines(FILE)).also { ids = it }

    /** Cópia dos favoritos atuais. */
    fun getAll(): Set<String> = synchronized(lock) { LinkedHashSet(loaded()) }

    fun setFavorite(id: String, favorite: Boolean) {
        synchronized(lock) {
            val set = loaded()
            val changed = if (favorite) set.add(id) else set.remove(id)
            if (changed) storage.writeLines(FILE, set.toList())
        }
    }

    private companion object {
        const val FILE = "favorites.txt"
    }
}
