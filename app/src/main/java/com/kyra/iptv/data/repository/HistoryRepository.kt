package com.kyra.iptv.data.repository

import com.kyra.iptv.storage.LocalStorage

/**
 * Histórico simples dos últimos canais assistidos (ids estáveis), do mais recente para o mais antigo,
 * limitado a [maxEntries]. Bloqueante (disco): chamar fora da thread principal.
 */
class HistoryRepository(
    private val storage: LocalStorage,
    private val maxEntries: Int = 50,
) {
    private val lock = Any()
    private var ids: ArrayList<String>? = null

    private fun loaded(): ArrayList<String> =
        ids ?: ArrayList(storage.readLines(FILE).distinct().take(maxEntries)).also { ids = it }

    /** Mais recente primeiro. */
    fun getAll(): List<String> = synchronized(lock) { loaded().toList() }

    fun record(id: String) {
        synchronized(lock) {
            val list = loaded()
            list.remove(id)
            list.add(0, id)
            while (list.size > maxEntries) list.removeAt(list.size - 1)
            storage.writeLines(FILE, list)
        }
    }

    private companion object {
        const val FILE = "history.txt"
    }
}
