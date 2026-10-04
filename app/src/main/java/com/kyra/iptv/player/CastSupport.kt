package com.kyra.iptv.player

/**
 * Regras puras (sem Android/Cast SDK) para transmitir um stream ao Chromecast.
 *
 * O `CastPlayer` exige o tipo MIME do item (não detecta sozinho como o ExoPlayer) e o receptor
 * acessa a URL diretamente: não há proxy, então headers personalizados não são enviados (PLANO.md §9).
 */
object CastSupport {
    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_DASH = "application/dash+xml"
    const val MIME_TS = "video/mp2t"
    const val MIME_MP4 = "video/mp4"

    /**
     * `.m3u8` → HLS, `.mpd` → DASH, `.ts` → MPEG-TS, `.mp4` → MP4. URLs sem extensão (comuns em
     * painéis, ex.: `/live/usuario/senha/123`) são assumidas como HLS, que é o formato que o
     * receptor padrão do Chromecast melhor suporta para ao vivo.
     */
    fun mimeType(url: String): String = when (StreamSupport.detectType(url)) {
        StreamType.HLS -> MIME_HLS
        StreamType.DASH -> MIME_DASH
        StreamType.UNKNOWN -> {
            val path = StreamSupport.pathOf(url)
            when {
                path.endsWith(".ts") -> MIME_TS
                path.endsWith(".mp4") -> MIME_MP4
                else -> MIME_HLS
            }
        }
    }

    /** O Chromecast não envia headers personalizados; se o canal depende deles, a transmissão pode falhar. */
    fun dependsOnHeaders(headers: Map<String, String>): Boolean =
        StreamSupport.sanitizeHeaders(headers).isNotEmpty()

    const val HEADERS_WARNING =
        "Este canal usa headers personalizados. O Chromecast não os envia e a transmissão pode falhar."

    const val REMOTE_ERROR =
        "O Chromecast não conseguiu reproduzir este canal. O receptor precisa acessar o stream " +
            "diretamente e pode não suportar o formato."
}
