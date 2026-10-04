package com.kyra.iptv.player

enum class StreamType { HLS, DASH, UNKNOWN }

/** Regras puras (sem Android/Media3) usadas antes de entregar uma URL ao player. */
object StreamSupport {

    private val HTTP_URL = Regex("^https?://[^\\s/?#]+\\S*$", RegexOption.IGNORE_CASE)
    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    /** Headers controlados pela pilha HTTP; uma playlist não deve (nem consegue) defini-los. */
    private val BLOCKED_HEADERS = setOf(
        "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "expect",
    )

    /** Só http/https com host, sem espaços nem caracteres de controle. */
    fun isPlayable(url: String): Boolean = HTTP_URL.matches(url.trim()) && url.none { it.isISOControl() }

    /**
     * Tipo pelo endereço. `UNKNOWN` (sem extensão, `.ts`, `.mp4`...) deixa o ExoPlayer detectar
     * o formato; se isso falhar por formato não reconhecido, a tela do player tenta HLS uma vez.
     */
    fun detectType(url: String): StreamType {
        val lower = url.trim().lowercase()
        val path = pathOf(url)
        return when {
            path.endsWith(".mpd") -> StreamType.DASH
            ".m3u8" in lower -> StreamType.HLS
            else -> StreamType.UNKNOWN
        }
    }

    /** Caminho da URL em minúsculas, sem query nem fragmento (para olhar a extensão). */
    fun pathOf(url: String): String = url.trim().lowercase().substringBefore('#').substringBefore('?')

    /**
     * Descarta headers inválidos (nome fora do padrão, valor vazio ou com quebra de linha) e os
     * controlados pela pilha HTTP (`Host`, `Content-Length`, `Connection`...).
     */
    fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val n = name.trim()
            val v = value.trim()
            if (n.isEmpty() || v.isEmpty()) continue
            if (!HEADER_NAME.matches(n)) continue
            if (n.lowercase() in BLOCKED_HEADERS) continue
            if (v.any { it.isISOControl() }) continue
            out[n] = v
        }
        return out
    }
}
