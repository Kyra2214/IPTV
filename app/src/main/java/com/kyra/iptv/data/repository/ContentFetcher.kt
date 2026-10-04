package com.kyra.iptv.data.repository

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Abre o conteúdo de uma URL. Separado do repositório para permitir testes sem rede. */
fun interface ContentFetcher {
    @Throws(IOException::class)
    fun open(url: String): InputStream
}

/**
 * Download HTTP/HTTPS com timeouts e redirects manuais (inclusive http → https),
 * aceitando apenas http/https em cada salto. Chamar fora da thread principal.
 */
class HttpContentFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 5,
    private val userAgent: String = "IPTV/0.1",
) : ContentFetcher {

    override fun open(url: String): InputStream {
        var current = url
        repeat(maxRedirects + 1) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            val code = conn.responseCode
            when {
                code in 200..299 -> return conn.inputStream
                code in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw PlaylistError.Network("Redirecionamento inválido")
                    val next = URL(URL(current), location).toString()
                    if (!next.startsWith("http://", true) && !next.startsWith("https://", true)) {
                        throw PlaylistError.Network("Redirecionamento para protocolo não suportado")
                    }
                    current = next
                }
                else -> {
                    conn.disconnect()
                    throw PlaylistError.Network("Servidor respondeu HTTP $code")
                }
            }
        }
        throw PlaylistError.Network("Redirecionamentos demais")
    }
}
