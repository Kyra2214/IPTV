package com.kyra.iptv.data.parser

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.player.StreamSupport
import java.net.URLEncoder

/**
 * Escreve canais como M3U que o [M3uParser] lê de volta com os mesmos dados (id, nome, grupo,
 * `tvg-*`, logo e headers). Sem dependência de Android; grava em streaming, canal a canal.
 *
 * `User-Agent`, `Referer` e `Origin` saem como `#EXTVLCOPT`; os demais headers vão no formato
 * `url|Nome=valor&...`. Valores de atributo não podem conter aspas duplas (o formato não tem
 * escape): elas viram aspas simples. Quebras de linha e controles viram espaço.
 */
object M3uExporter {

    /** Grava [channels] em [out] e devolve quantos foram escritos (canais com URL inutilizável são pulados). */
    fun write(channels: Iterable<Channel>, out: Appendable): Int {
        writeHeader(out)
        var written = 0
        for (ch in channels) if (writeChannel(ch, out)) written++
        return written
    }

    /** Escrita canal a canal (para quem lê de várias fontes sem juntar tudo na memória): primeiro o cabeçalho... */
    fun writeHeader(out: Appendable) {
        out.append("#EXTM3U\n")
    }

    /** ...depois cada canal. `false` se a URL é inutilizável e o canal foi pulado. */
    fun writeChannel(ch: Channel, out: Appendable): Boolean {
        if (!StreamSupport.isPlayable(ch.streamUrl)) return false
        writeEntry(ch, out)
        return true
    }

    private fun writeEntry(ch: Channel, out: Appendable) {
        val headers = StreamSupport.sanitizeHeaders(ch.headers)

        out.append("#EXTINF:-1")
        attr(out, "tvg-id", ch.tvgId)
        attr(out, "tvg-name", ch.tvgName)
        attr(out, "tvg-logo", ch.logoUrl)
        if (ch.group != Channel.UNGROUPED) attr(out, "group-title", ch.group)
        out.append(',').append(oneLine(ch.name).ifEmpty { "Canal" }).append('\n')

        val pipe = ArrayList<String>()
        for ((name, value) in headers) {
            when (name.lowercase()) {
                "user-agent" -> out.append("#EXTVLCOPT:http-user-agent=").append(value).append('\n')
                "referer", "referrer" -> out.append("#EXTVLCOPT:http-referrer=").append(value).append('\n')
                "origin" -> out.append("#EXTVLCOPT:http-origin=").append(value).append('\n')
                else -> if ('&' !in name && '|' !in name && '=' !in name) {
                    pipe += "$name=${URLEncoder.encode(value, "UTF-8")}"
                }
            }
        }
        out.append(ch.streamUrl.trim())
        if (pipe.isNotEmpty()) out.append('|').append(pipe.joinToString("&"))
        out.append('\n')
    }

    private fun attr(out: Appendable, key: String, value: String?) {
        val clean = oneLine(value.orEmpty()).replace('"', '\'')
        if (clean.isNotEmpty()) out.append(' ').append(key).append("=\"").append(clean).append('"')
    }

    private fun oneLine(s: String): String =
        buildString(s.length) { for (c in s) append(if (c.isISOControl()) ' ' else c) }.trim()
}
