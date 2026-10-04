package com.kyra.iptv.data.testing

import com.kyra.iptv.data.parser.M3uParser
import java.io.Reader

/** Análise da lista antes do teste (etapa 5): contadores do parser + quantidade de grupos. */
object ListAnalyzer {

    /**
     * Lê a lista em streaming (sem acumular canais) e devolve os números da tela "Analisar lista".
     * Guarda só os nomes de grupo distintos, que o parser já internou. [reader] é fechado.
     */
    fun analyze(reader: Reader, baseUrl: String? = null): ListAnalysis {
        val groups = HashSet<String>()
        val stats = M3uParser.scan(reader, baseUrl) { groups += it.group }
        return ListAnalysis.from(stats, groups.size)
    }
}
