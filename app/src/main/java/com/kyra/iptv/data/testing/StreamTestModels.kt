package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.data.parser.ParseStats

/**
 * Modelos da fase "Testar lista / Garimpo" (PLANO-GARIMPO.md). Kotlin puro, sem Android:
 * tudo aqui é testável na JVM. Nenhum texto produzido por estes modelos contém a URL do stream.
 */

/** Filtros da tela de resultados. */
enum class TestFilter { ALL, WORKING, FAILING, HTTP_403, HTTP_404, HTTP_411, TIMEOUT, HTTP_5XX, OTHER }

/**
 * Resultado do teste de um canal.
 *
 * [bucket] é o filtro de falha em que o resultado cai (`null` para [WORKING]). Timeouts e 408
 * ficam juntos em [TestFilter.TIMEOUT]; 401, 429 e os demais erros ficam em [TestFilter.OTHER]
 * (o motivo exato aparece em [label]).
 */
enum class TestOutcome(val label: String, val bucket: TestFilter?) {
    WORKING("Funcionando", null),

    HTTP_401("401 Unauthorized", TestFilter.OTHER),
    HTTP_403("403 Forbidden", TestFilter.HTTP_403),
    HTTP_404("404 Not Found", TestFilter.HTTP_404),
    HTTP_408("408 Timeout", TestFilter.TIMEOUT),
    HTTP_411("411 Length Required", TestFilter.HTTP_411),
    HTTP_429("429 Too Many Requests", TestFilter.OTHER),
    HTTP_5XX("Erro 5xx do servidor", TestFilter.HTTP_5XX),
    HTTP_OTHER("Outro erro HTTP", TestFilter.OTHER),

    CONNECT_TIMEOUT("Timeout de conexão", TestFilter.TIMEOUT),
    PREPARE_TIMEOUT("Timeout de preparação", TestFilter.TIMEOUT),
    CONFIRM_TIMEOUT("Timeout de confirmação", TestFilter.TIMEOUT),

    DNS("Erro de DNS", TestFilter.OTHER),
    NETWORK("Erro de rede", TestFilter.OTHER),
    FORMAT("Formato não reconhecido", TestFilter.OTHER),
    DECODER("Erro de decodificação", TestFilter.OTHER),
    INCOMPATIBLE("Resposta não reproduzível", TestFilter.OTHER),
    CLEARTEXT("Conexão sem criptografia não permitida", TestFilter.OTHER),
    OTHER("Outro erro", TestFilter.OTHER);

    val isWorking: Boolean get() = this == WORKING

    /** Este resultado aparece sob o [filter]? */
    fun matches(filter: TestFilter): Boolean = when (filter) {
        TestFilter.ALL -> true
        TestFilter.WORKING -> isWorking
        TestFilter.FAILING -> !isWorking
        else -> bucket == filter
    }
}

/**
 * Resultado completo de um canal. [channel] guarda id (hash da URL, inalterado), nome, URL, grupo,
 * tvg-id/tvg-name, logo e headers. [reason] é um texto curto para a interface e NUNCA contém a URL.
 */
data class StreamTestResult(
    val channel: Channel,
    val outcome: TestOutcome,
    /** Código HTTP quando conhecido (nos aprovados é opcional/melhor esforço). */
    val httpStatus: Int?,
    val reason: String,
    val testedAt: Long,
    val elapsedMs: Long,
) {
    val isWorking: Boolean get() = outcome.isWorking
}

/**
 * Configuração centralizada do teste: nenhum limite ou timeout fica solto pelo código.
 * Valores iniciais, a validar em aparelho (PLANO-GARIMPO.md §2.3).
 */
data class StreamTestConfig(
    /** Testes simultâneos (1..[MAX_CONCURRENCY]). */
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    /** Tempo máximo até o player ficar pronto (`STATE_READY`). */
    val prepareTimeoutMs: Long = DEFAULT_PREPARE_TIMEOUT_MS,
    /** Tempo máximo, depois de pronto, para haver mídia em buffer. */
    val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    /** Buffer mínimo para considerar que chegou mídia de verdade. */
    val confirmBufferedMs: Long = DEFAULT_CONFIRM_BUFFERED_MS,
    /** Tamanho da fila entre a leitura da lista e os testes (a lista nunca fica inteira na fila). */
    val feederQueueSize: Int = DEFAULT_FEEDER_QUEUE_SIZE,
    /** Folga sobre a soma dos timeouts antes de o motor dar o teste por travado (rede de segurança). */
    val watchdogMarginMs: Long = DEFAULT_WATCHDOG_MARGIN_MS,
) {
    init {
        require(concurrency in 1..MAX_CONCURRENCY) { "concurrency fora de 1..$MAX_CONCURRENCY" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs deve ser positivo" }
        require(readTimeoutMs > 0) { "readTimeoutMs deve ser positivo" }
        require(prepareTimeoutMs > 0) { "prepareTimeoutMs deve ser positivo" }
        require(confirmTimeoutMs > 0) { "confirmTimeoutMs deve ser positivo" }
        require(confirmBufferedMs >= 0) { "confirmBufferedMs não pode ser negativo" }
        require(feederQueueSize >= 1) { "feederQueueSize deve ser pelo menos 1" }
        require(watchdogMarginMs >= 0) { "watchdogMarginMs não pode ser negativo" }
    }

    /**
     * Prazo máximo de um teste inteiro. Um teste que passar disso (sonda travada) é cancelado pelo
     * motor e contado como falha, para nunca prender um slot para sempre.
     */
    val hardTimeoutMs: Long
        get() = connectTimeoutMs.toLong() + prepareTimeoutMs + confirmTimeoutMs + watchdogMarginMs

    companion object {
        const val MAX_CONCURRENCY = 8
        const val DEFAULT_CONCURRENCY = 4
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        const val DEFAULT_READ_TIMEOUT_MS = 8_000
        const val DEFAULT_PREPARE_TIMEOUT_MS = 15_000L
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 5_000L
        const val DEFAULT_CONFIRM_BUFFERED_MS = 500L
        const val DEFAULT_FEEDER_QUEUE_SIZE = 64
        const val DEFAULT_WATCHDOG_MARGIN_MS = 5_000L
    }
}

/**
 * Estatísticas da lista antes do teste.
 *
 * O parser tem um único contador de descartes ([invalid]: sem URL, URL inválida, esquema não
 * http/https) e outro de repetidas ([duplicates]); por isso "descartadas pelo parser" é a soma dos dois.
 */
data class ListAnalysis(
    val totalEntries: Int,
    val uniqueUrls: Int,
    val duplicates: Int,
    val invalid: Int,
    val groups: Int,
) {
    val discardedByParser: Int get() = invalid + duplicates

    companion object {
        fun from(stats: ParseStats, groups: Int): ListAnalysis = ListAnalysis(
            totalEntries = stats.count + stats.skipped + stats.duplicates,
            uniqueUrls = stats.count,
            duplicates = stats.duplicates,
            invalid = stats.skipped,
            groups = groups,
        )
    }
}

/** Foto imutável do andamento, entregue à interface. */
data class TestProgress(
    val total: Int,
    val tested: Int,
    val working: Int,
    private val counts: Map<TestFilter, Int>,
) {
    val failed: Int get() = tested - working

    /** 0..100 (inteiro); 0 quando a lista está vazia. */
    val percent: Int get() = if (total <= 0) 0 else ((tested.toLong() * 100) / total).toInt().coerceIn(0, 100)

    val finished: Boolean get() = total > 0 && tested >= total

    /** Quantidade de resultados sob o [filter] (para os chips da tela). */
    fun count(filter: TestFilter): Int = counts[filter] ?: 0
}

/**
 * Contadores por resultado. Não é thread-safe: pertence ao motor de teste, que o acessa
 * sempre pela mesma thread de coordenação.
 */
class TestTally {
    private val byOutcome = IntArray(TestOutcome.values().size)

    var tested: Int = 0
        private set

    val working: Int get() = byOutcome[TestOutcome.WORKING.ordinal]

    fun record(outcome: TestOutcome) {
        byOutcome[outcome.ordinal]++
        tested++
    }

    fun count(outcome: TestOutcome): Int = byOutcome[outcome.ordinal]

    fun count(filter: TestFilter): Int =
        TestOutcome.values().sumOf { if (it.matches(filter)) byOutcome[it.ordinal] else 0 }

    fun snapshot(total: Int): TestProgress =
        TestProgress(
            total = total,
            tested = tested,
            working = working,
            counts = TestFilter.values().associateWith { count(it) },
        )
}
