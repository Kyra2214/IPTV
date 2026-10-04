package com.kyra.iptv.data.parser

import com.kyra.iptv.data.model.Channel
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder

/** Resultado do parse: canais válidos + contadores do que foi descartado. */
data class ParseResult(
    val channels: List<Channel>,
    /** Entradas descartadas (sem URL, URL inválida, esquema não suportado...). */
    val skipped: Int,
    /** Entradas descartadas por repetirem a URL de um canal anterior. */
    val duplicates: Int,
)

/** Contadores de uma leitura em streaming ([M3uParser.scan]); não guarda os canais. */
data class ParseStats(
    /** Canais válidos entregues a quem chamou. */
    val count: Int,
    val skipped: Int,
    val duplicates: Int,
    /** Viu tags de playlist HLS de um único stream (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`...). */
    val hlsStreamTags: Boolean,
    /** Alguma entrada tinha `tvg-id`, `tvg-name`, `tvg-logo` ou `group-title` (cara de lista IPTV). */
    val iptvAttributes: Boolean,
) {
    /** Um stream HLS (master/media playlist) colado como se fosse lista de canais. */
    val looksLikeHlsStream: Boolean get() = hlsStreamTags && !iptvAttributes
}

/**
 * Parser M3U/M3U8 tolerante, sem dependência de Android (testável na JVM).
 *
 * - Uma entrada inválida nunca interrompe o restante da playlist.
 * - Preserva a ordem original; remove duplicatas óbvias (mesma URL), mantendo a primeira.
 * - Lê linha a linha de um [Reader], sem exigir a playlist inteira como String.
 * - Aceita apenas `http://` e `https://` (ou URL relativa resolvida com `baseUrl`).
 */
object M3uParser {

    private val ATTR = Regex("""([A-Za-z0-9_.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"',]+))""")
    private val ABSOLUTE_HTTP = Regex("""(?i)^https?://[^/?#\s]+\S*$""")

    fun parse(content: String, baseUrl: String? = null): ParseResult =
        parse(StringReader(content), baseUrl)

    fun parse(reader: Reader, baseUrl: String? = null): ParseResult {
        val channels = ArrayList<Channel>()
        val stats = scan(reader, baseUrl) { channels += it }
        return ParseResult(channels, stats.skipped, stats.duplicates)
    }

    /**
     * Lê a playlist em streaming e entrega cada canal válido a [onChannel], sem acumular a lista:
     * serve para contar/validar listas enormes sem mantê-las inteiras na memória. Se [onChannel]
     * lançar uma exceção, a leitura é interrompida e a exceção chega a quem chamou.
     */
    fun scan(reader: Reader, baseUrl: String? = null, onChannel: (Channel) -> Unit): ParseStats {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URI(it) }.getOrNull() }

        val seenIds = HashSet<String>()
        val groupPool = HashMap<String, String>() // um único String por nome de grupo (economiza memória)
        var count = 0
        var skipped = 0
        var duplicates = 0
        var hlsStreamTags = false
        var iptvAttributes = false
        var pending: Pending? = null

        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim { it.isWhitespace() || it == '\uFEFF' }
                if (line.isEmpty()) continue

                if (line.startsWith("#")) {
                    when {
                        line.startsWith("#EXTINF", ignoreCase = true) -> {
                            if (pending != null) skipped++ // EXTINF anterior ficou sem URL
                            pending = parseExtInf(line)
                        }
                        line.startsWith("#EXTVLCOPT:", ignoreCase = true) ->
                            pending?.applyVlcOpt(line.substringAfter(':'))
                        line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                            pending?.extGroup = line.substringAfter(':').trim()
                        }
                        isHlsStreamTag(line) -> hlsStreamTags = true
                        // #EXTM3U e quaisquer outros comentários: ignorados.
                    }
                    continue
                }

                // Linha de URL: fecha a entrada pendente (ou é uma URL "solta").
                val entry = pending ?: Pending()
                pending = null

                val pipeIdx = line.indexOf('|')
                val rawUrl = (if (pipeIdx >= 0) line.substring(0, pipeIdx) else line).trim()
                val url = resolveUrl(rawUrl, base)
                if (url == null) {
                    skipped++
                    continue
                }
                if (pipeIdx >= 0) entry.applyPipeHeaders(line.substring(pipeIdx + 1))
                if (entry.hasIptvAttributes()) iptvAttributes = true

                val id = ChannelId.fromStreamUrl(url)
                if (!seenIds.add(id)) {
                    duplicates++
                    continue
                }

                val tvgName = entry.attrs["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() }
                val group = (entry.attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: entry.extGroup?.takeIf { it.isNotEmpty() })
                    ?.let { g -> groupPool.getOrPut(g) { g } }
                    ?: Channel.UNGROUPED

                count++
                onChannel(
                    Channel(
                        id = id,
                        name = tvgName ?: entry.displayName.takeIf { it.isNotEmpty() } ?: fallbackName(url),
                        streamUrl = url,
                        group = group,
                        tvgId = entry.attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
                        tvgName = tvgName,
                        logoUrl = entry.attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() },
                        headers = entry.headers.toMap(),
                    )
                )
            }
        }

        if (pending != null) skipped++ // EXTINF final sem URL
        return ParseStats(count, skipped, duplicates, hlsStreamTags, iptvAttributes)
    }

    private fun isHlsStreamTag(line: String): Boolean =
        line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
            line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true)

    // ---- EXTINF -------------------------------------------------------------

    private class Pending {
        val attrs = HashMap<String, String>()
        var displayName: String = ""
        /** Grupo vindo de `#EXTGRP:`; só vale quando não há `group-title`. */
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()

        fun hasIptvAttributes(): Boolean = attrs.containsKey("tvg-id") || attrs.containsKey("tvg-name") ||
            attrs.containsKey("tvg-logo") || attrs.containsKey("group-title")

        fun applyVlcOpt(opt: String) {
            val key = opt.substringBefore('=').trim().lowercase()
            val value = opt.substringAfter('=', "").trim().trim('"', '\'')
            if (value.isEmpty()) return
            when (key) {
                "http-user-agent" -> headers["User-Agent"] = value
                "http-referrer", "http-referer" -> headers["Referer"] = value
                "http-origin" -> headers["Origin"] = value
            }
        }

        /** Formato estilo Kodi: `url|User-Agent=x&Referer=y`. */
        fun applyPipeHeaders(spec: String) {
            for (part in spec.split('&')) {
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) continue
                val value = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                val name = when (k.lowercase()) {
                    "user-agent" -> "User-Agent"
                    "referer", "referrer" -> "Referer"
                    "origin" -> "Origin"
                    else -> k
                }
                headers[name] = value
            }
        }
    }

    private fun parseExtInf(line: String): Pending {
        val body = line.substring(7).removePrefix(":")
        val comma = findNameComma(body)
        val attrsPart = if (comma >= 0) body.substring(0, comma) else body
        val pending = Pending()
        pending.displayName = if (comma >= 0) body.substring(comma + 1).trim() else ""
        for (m in ATTR.findAll(attrsPart)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            pending.attrs.putIfAbsent(key, value)
        }
        return pending
    }

    /**
     * Posição da vírgula que separa atributos do nome exibido, ignorando vírgulas dentro
     * de valores entre aspas. Uma aspa só abre valor quando vem logo após `=`; assim um
     * apóstrofo solto não "engole" a vírgula. Se nada for achado, usa a primeira vírgula.
     */
    private fun findNameComma(s: String): Int {
        var quote = 0.toChar()
        var prevSignificant = 0.toChar()
        for (i in s.indices) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
                continue
            }
            when {
                (c == '"' || c == '\'') && prevSignificant == '=' -> quote = c
                c == ',' -> return i
            }
            if (!c.isWhitespace()) prevSignificant = c
        }
        return s.indexOf(',')
    }

    // ---- URL ----------------------------------------------------------------

    private fun resolveUrl(raw: String, base: URI?): String? {
        if (raw.isEmpty()) return null
        if (ABSOLUTE_HTTP.matches(raw)) return raw
        if (raw.contains("://") || raw.any { it.isWhitespace() }) return null // outro esquema / lixo
        if (base == null) return null
        val resolved = runCatching { base.resolve(raw).toString() }.getOrNull() ?: return null
        return resolved.takeIf { ABSOLUTE_HTTP.matches(it) }
    }

    /** Nome seguro quando não há `tvg-name` nem nome após a vírgula: `host/último-segmento`. */
    private fun fallbackName(url: String): String {
        val noScheme = url.substringAfter("://")
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val path = noScheme.substring(host.length).substringBefore('?').substringBefore('#')
        val last = path.trimEnd('/').substringAfterLast('/')
        return if (last.isEmpty()) host else "$host/$last"
    }
}
