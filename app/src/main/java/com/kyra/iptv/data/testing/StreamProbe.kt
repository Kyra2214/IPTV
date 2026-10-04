package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel

/** Cancela um teste em andamento. */
fun interface ProbeHandle {
    fun cancel()
}

/**
 * Testa UM stream. A implementação real (`ExoStreamProbe`, Android/Media3) vem na etapa 4;
 * o [StreamTestEngine] só conhece esta interface, então é testável na JVM com sondas falsas.
 *
 * Contrato:
 * - [start] não bloqueia; devolve na hora um [ProbeHandle];
 * - [onDone] deve ser chamado **exatamente uma vez**, de qualquer thread, inclusive de dentro de
 *   [start] e inclusive depois de [ProbeHandle.cancel] (depois de liberar os recursos, como o ExoPlayer);
 * - chamadas extras de [onDone] são ignoradas pelo motor.
 */
interface StreamProbe {
    fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle
}

/**
 * Fonte de canais lida **um a um**, para a lista nunca ficar inteira na memória. A implementação
 * real lê o arquivo temporário em streaming com `M3uParser.scan` (etapa 5).
 */
interface ChannelSource : AutoCloseable {
    /** Próximo canal, ou `null` no fim (ou depois de [close]). Pode bloquear brevemente. */
    fun next(): Channel?

    /** Libera a leitura; depois disso [next] devolve `null`. */
    override fun close()
}

/** Fonte sobre um [Iterator] (listas já em memória, testes). */
class IteratorChannelSource(private val iterator: Iterator<Channel>) : ChannelSource {
    @Volatile private var closed = false

    override fun next(): Channel? = if (!closed && iterator.hasNext()) iterator.next() else null

    override fun close() {
        closed = true
    }
}
