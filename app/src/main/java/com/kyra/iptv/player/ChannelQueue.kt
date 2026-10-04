package com.kyra.iptv.player

import com.kyra.iptv.data.model.Channel

/**
 * Fila de reprodução: os canais que o usuário estava vendo (aba/grupo/busca) e a posição atual.
 * Canal anterior/próximo dão a volta nas pontas. Sem dependência de Android.
 */
class ChannelQueue(val channels: List<Channel>, startIndex: Int = 0) {

    var index: Int = startIndex.coerceIn(0, maxOf(0, channels.size - 1))
        private set

    val size: Int get() = channels.size
    val hasMultiple: Boolean get() = channels.size > 1
    val current: Channel? get() = channels.getOrNull(index)

    fun next(): Channel? {
        if (channels.isEmpty()) return null
        index = (index + 1) % channels.size
        return current
    }

    fun previous(): Channel? {
        if (channels.isEmpty()) return null
        index = (index - 1 + channels.size) % channels.size
        return current
    }

    companion object {
        /** Fila que começa no canal de id [channelId]; se não existir, começa no primeiro. */
        fun startingAt(channels: List<Channel>, channelId: String?): ChannelQueue {
            val i = if (channelId == null) 0 else channels.indexOfFirst { it.id == channelId }
            return ChannelQueue(channels, if (i < 0) 0 else i)
        }
    }
}
