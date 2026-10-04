package com.kyra.iptv.data.parser

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Detecção simples de codificação para listas M3U (sem Android, testável na JVM).
 *
 * Listas reais costumam ser UTF-8, mas há UTF-16 com BOM (arquivos salvos pelo Notepad) e listas
 * antigas em Windows-1252/Latin-1 (acentos viram "�" se lidas como UTF-8). A lista é normalizada
 * para UTF-8 ao ser importada; o resto do app só lê UTF-8.
 */
object TextEncoding {

    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** BOM UTF-16 → UTF-16; UTF-8 válido (com ou sem BOM) → UTF-8; senão Windows-1252. */
    fun detect(file: File): Charset {
        val head = ByteArray(2)
        val read = file.inputStream().use { it.read(head) }
        if (read == 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return Charsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return Charsets.UTF_16BE
        }
        return if (isValidUtf8(file)) Charsets.UTF_8 else WINDOWS_1252
    }

    /** Se [file] não estiver em UTF-8, grava uma cópia UTF-8 em [dest] e devolve `true`. */
    fun transcodeToUtf8IfNeeded(file: File, dest: File): Boolean {
        val charset = detect(file)
        if (charset == Charsets.UTF_8) return false
        file.reader(charset).use { r ->
            dest.writer(Charsets.UTF_8).use { w -> r.copyTo(w) }
        }
        return true
    }

    private fun isValidUtf8(file: File): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            InputStreamReader(file.inputStream().buffered(), decoder).use { r ->
                val buf = CharArray(16 * 1024)
                while (r.read(buf) >= 0) {
                    // só percorre: um byte inválido lança CharacterCodingException
                }
            }
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }
}
