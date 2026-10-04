package com.kyra.iptv.data.model

/**
 * Canal de uma playlist.
 *
 * [id] é determinístico: hash da URL do stream (`ChannelId`). Favoritos e histórico usam este id.
 */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String = UNGROUPED,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    /** Headers HTTP necessários para o stream, quando aplicável. */
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        const val UNGROUPED = "Ungrouped"
    }
}
