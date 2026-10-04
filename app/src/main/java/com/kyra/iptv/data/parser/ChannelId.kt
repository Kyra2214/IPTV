package com.kyra.iptv.data.parser

import java.security.MessageDigest

/**
 * Identidade determinística de canal.
 *
 * Derivada apenas da URL do stream (SHA-256, 32 caracteres hex), então o mesmo canal
 * mantém o mesmo id entre atualizações da lista. `tvg-id` NÃO entra no cálculo: ele é
 * frequentemente repetido (ex.: mesmo canal em várias qualidades) ou ausente.
 * Favoritos e histórico usam este id.
 */
object ChannelId {
    private val HEX = "0123456789abcdef".toCharArray()

    fun fromStreamUrl(streamUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(streamUrl.trim().toByteArray(Charsets.UTF_8))
        val out = CharArray(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }
}
