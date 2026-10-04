package com.kyra.iptv.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import java.net.UnknownHostException

/**
 * Peças do Media3 compartilhadas entre a tela do player e o teste de lista (`ExoStreamProbe`),
 * para as duas lerem o stream exatamente do mesmo jeito (headers, redirects, tipo, tradução de erro).
 * Depende de Android/Media3; a lógica pura continua em [StreamSupport] e [PlaybackErrorPolicy].
 */
@UnstableApi
object MediaSupport {

    /** Fábrica HTTP com os headers do canal (sanitizados), redirects entre http/https e timeouts. */
    fun httpFactory(headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): DefaultHttpDataSource.Factory {
        val clean = StreamSupport.sanitizeHeaders(headers)
        val userAgent = clean.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val others = clean.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
        if (userAgent != null) http.setUserAgent(userAgent)
        if (others.isNotEmpty()) http.setDefaultRequestProperties(others)
        return http
    }

    /** `MediaItem` com o tipo explícito quando o endereço o revela; `UNKNOWN` deixa o ExoPlayer detectar. */
    fun mediaItem(url: String, type: StreamType): MediaItem =
        MediaItem.Builder().setUri(url).apply {
            when (type) {
                StreamType.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                StreamType.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                StreamType.UNKNOWN -> Unit
            }
        }.build()

    /** Traduz o erro do Media3 para o modelo puro de [PlaybackErrorPolicy]. */
    fun toFailure(e: PlaybackException): StreamFailure {
        val status = httpStatusOf(e)
        val kind = when (e.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureKind.BEHIND_LIVE_WINDOW
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureKind.TIMEOUT
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> FailureKind.NETWORK
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> FailureKind.CLEARTEXT_NOT_PERMITTED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> FailureKind.UNRECOGNIZED_FORMAT
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> FailureKind.DECODER
            else -> FailureKind.OTHER
        }
        return StreamFailure(kind, status)
    }

    /** Código HTTP da resposta de erro, procurando em toda a cadeia de causas (o HLS embrulha o erro). */
    fun httpStatusOf(e: Throwable): Int? = causes(e)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

    /** O erro veio de um host que não resolve (DNS)? */
    fun isUnknownHost(e: Throwable): Boolean = causes(e).any { it is UnknownHostException }

    private fun causes(e: Throwable): Sequence<Throwable> =
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}
