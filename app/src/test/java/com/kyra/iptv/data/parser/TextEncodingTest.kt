package com.kyra.iptv.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextEncodingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray): File = tmp.newFile().also { it.writeBytes(bytes) }

    private val text = "Notícias: Globo Ação\n"

    @Test fun utf8WithAndWithoutBomIsUtf8() {
        assertEquals(Charsets.UTF_8, TextEncoding.detect(file(text.toByteArray(Charsets.UTF_8))))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, TextEncoding.detect(file(bom)))
    }

    @Test fun emptyAndTinyFilesAreUtf8() {
        assertEquals(Charsets.UTF_8, TextEncoding.detect(file(ByteArray(0))))
        assertEquals(Charsets.UTF_8, TextEncoding.detect(file(byteArrayOf('a'.code.toByte()))))
    }

    @Test fun utf16BomIsDetectedInBothByteOrders() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16LE, TextEncoding.detect(file(le)))
        assertEquals(Charsets.UTF_16BE, TextEncoding.detect(file(be)))
    }

    @Test fun invalidUtf8FallsBackToASingleByteCharset() {
        val latin1 = text.toByteArray(Charsets.ISO_8859_1)
        assertNotEquals(Charsets.UTF_8, TextEncoding.detect(file(latin1)))
    }

    @Test fun utf8InputIsNotRewritten() {
        val src = file(text.toByteArray(Charsets.UTF_8))
        val dest = tmp.newFile()
        assertFalse(TextEncoding.transcodeToUtf8IfNeeded(src, dest))
        assertEquals(0L, dest.length())
    }

    @Test fun latin1IsTranscodedToUtf8() {
        val src = file(text.toByteArray(Charsets.ISO_8859_1))
        val dest = tmp.newFile()
        assertTrue(TextEncoding.transcodeToUtf8IfNeeded(src, dest))
        assertEquals(text, dest.readText(Charsets.UTF_8))
    }

    @Test fun windows1252CurlyQuotesSurvive() {
        // 0x93 e 0x94 são aspas curvas no Windows-1252 (e caracteres de controle no Latin-1 puro).
        val src = file(byteArrayOf(0x93.toByte(), 'a'.code.toByte(), 0x94.toByte()))
        val dest = tmp.newFile()
        assertTrue(TextEncoding.transcodeToUtf8IfNeeded(src, dest))
        assertEquals("\u201Ca\u201D", dest.readText(Charsets.UTF_8))
    }

    @Test fun utf16IsTranscodedAndKeepsContent() {
        val src = file(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))
        val dest = tmp.newFile()
        assertTrue(TextEncoding.transcodeToUtf8IfNeeded(src, dest))
        assertEquals(text, dest.readText(Charsets.UTF_8).trimStart('\uFEFF'))
    }
}
