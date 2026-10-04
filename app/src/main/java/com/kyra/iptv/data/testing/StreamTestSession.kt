package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Um teste de lista, do ponto de vista da interface (Kotlin puro). Guarda o que a tela precisa para
 * sobreviver à recriação da Activity: a análise, o motor e os resultados. Uso único: depois de
 * FINISHED/CANCELLED, "testar de novo" cria outra sessão.
 *
 * - [openSource] abre a fonte de canais só quando o teste começa (e pode lançar, ex.: lista apagada);
 * - os ouvintes são chamados na thread do motor; quem escuta deve postar na thread principal;
 * - antes de [start], [state] é [EngineState.IDLE] e [progress] mostra 0 de [ListAnalysis.uniqueUrls].
 */
class StreamTestSession(
    val playlistId: String,
    val playlistName: String,
    val analysis: ListAnalysis,
    private val openSource: () -> ChannelSource,
    private val probe: StreamProbe,
) {
    private val listeners = CopyOnWriteArraySet<StreamTestListener>()
    private val startLock = Any()
    @Volatile private var engine: StreamTestEngine? = null

    private val forwarder = object : StreamTestListener {
        override fun onProgress(progress: TestProgress) {
            for (l in listeners) runCatching { l.onProgress(progress) }
        }

        override fun onStateChanged(state: EngineState) {
            for (l in listeners) runCatching { l.onStateChanged(state) }
        }
    }

    fun addListener(l: StreamTestListener) {
        listeners += l
    }

    fun removeListener(l: StreamTestListener) {
        listeners -= l
    }

    val state: EngineState get() = engine?.state ?: EngineState.IDLE

    val started: Boolean get() = engine != null

    /** A leitura da lista falhou no meio: só foi testado o que veio antes. */
    val sourceFailed: Boolean get() = engine?.sourceFailed ?: false

    /**
     * Começa o teste. `false` se já havia começado. Se [openSource] lançar, a exceção sobe e a
     * sessão continua sem ter começado (dá para tentar de novo).
     */
    fun start(config: StreamTestConfig = StreamTestConfig()): Boolean = synchronized(startLock) {
        if (engine != null) return false
        val e = StreamTestEngine(
            source = openSource(),
            total = analysis.uniqueUrls,
            probe = probe,
            config = config,
            listener = forwarder,
        )
        engine = e
        e.start()
        true
    }

    fun pause() {
        engine?.pause()
    }

    fun resume() {
        engine?.resume()
    }

    fun cancel() {
        engine?.cancel()
    }

    fun progress(): TestProgress = engine?.progress() ?: TestTally().snapshot(analysis.uniqueUrls)

    fun results(filter: TestFilter = TestFilter.ALL): List<StreamTestResult> =
        engine?.results(filter) ?: emptyList()

    /** Os canais aprovados, para "Salvar funcionais". */
    fun workingChannels(): List<Channel> = engine?.workingChannels() ?: emptyList()
}
