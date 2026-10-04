package com.kyra.iptv.data.testing

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ListAnalyzerTest {

    private val text = """
        #EXTM3U
        #EXTINF:-1 group-title="Esportes",A
        http://x.example/a
        #EXTINF:-1 group-title="Esportes",B
        http://x.example/b
        #EXTINF:-1 group-title="Filmes",C
        http://x.example/c
        #EXTINF:-1 group-title="Filmes",A de novo
        http://x.example/a
        #EXTINF:-1,Ftp
        ftp://x.example/f
    """.trimIndent()

    @Test fun countsEntriesUniqueDuplicatesInvalidAndGroups() {
        val a = ListAnalyzer.analyze(StringReader(text))
        assertEquals(5, a.totalEntries)
        assertEquals(3, a.uniqueUrls)
        assertEquals(1, a.duplicates)
        assertEquals(1, a.invalid)
        assertEquals(2, a.groups)
        assertEquals(2, a.discardedByParser)
    }

    @Test fun channelsWithoutGroupCountAsOneExtraGroup() {
        val a = ListAnalyzer.analyze(StringReader(text + "\n#EXTINF:-1,Solto\nhttp://x.example/solto\n"))
        assertEquals(4, a.uniqueUrls)
        assertEquals(3, a.groups)
    }

    @Test fun emptyListHasZeroEverything() {
        val a = ListAnalyzer.analyze(StringReader("#EXTM3U\n"))
        assertEquals(ListAnalysis(0, 0, 0, 0, 0), a)
    }

    @Test fun relativeUrlsCountOnlyWithABase() {
        val rel = "#EXTM3U\n#EXTINF:-1,R\nlive/1.m3u8\n"
        assertEquals(0, ListAnalyzer.analyze(StringReader(rel)).uniqueUrls)
        assertEquals(1, ListAnalyzer.analyze(StringReader(rel), "http://h.example/a.m3u").uniqueUrls)
    }
}
