package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.data.parser.M3uParser
import java.io.Reader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [ChannelSource] real: lê o M3U salvo em streaming com [M3uParser.scan], sem nunca carregar a lista
 * inteira. O `scan` empurra canais para um callback; aqui eles passam por uma fila limitada
 * ([queueSize]) para o motor puxá-los um a um. Se o motor demora, o leitor espera (a fila enche e
 * a leitura do arquivo para): a memória fica proporcional à fila, não à lista.
 *
 * - A leitura só começa no primeiro [next] (criar a fonte não abre o arquivo).
 * - [close] pode ser chamado de qualquer thread e a qualquer momento: o leitor para no próximo canal
 *   (ou ao perceber a fila cheia) e o [Reader] é fechado pelo próprio `scan`.
 * - Erro de leitura (arquivo sumiu, disco): os canais já lidos continuam sendo entregues; depois o
 *   [next] lança a exceção (o motor marca `sourceFailed` e encerra com o que testou).
 * - O parser já descarta URLs repetidas; o motor ainda confere por id (defesa dupla).
 *
 * [next] bloqueia até haver um canal, o fim ou o [close]. Em arquivo local isso é rápido; só um
 * trecho enorme de linhas inválidas sem nenhum canal válido faz esperar mais.
 */
class ParserChannelSource(
    private val openReader: () -> Reader,
    private val baseUrl: String? = null,
    queueSize: Int = StreamTestConfig.DEFAULT_FEEDER_QUEUE_SIZE,
    private val pollMs: Long = DEFAULT_POLL_MS,
) : ChannelSource {

    private object End
    private class Failure(val error: Throwable)
    /** Lançada dentro do callback do `scan` para interromper a leitura depois do [close]. */
    private class Stop : RuntimeException(null, null, false, false)

    private val queue = ArrayBlockingQueue<Any>(queueSize.coerceAtLeast(1))
    private val startLock = Any()
    private var started = false

    @Volatile private var closed = false
    @Volatile private var finished = false

    override fun next(): Channel? {
        if (closed || finished) return null
        ensureStarted()
        while (true) {
            if (closed) return null
            when (val item = queue.poll(pollMs, TimeUnit.MILLISECONDS)) {
                null -> continue
                is Channel -> return item
                is Failure -> {
                    finished = true
                    throw item.error
                }
                else -> { // End
                    finished = true
                    return null
                }
            }
        }
    }

    override fun close() {
        closed = true
        queue.clear() // solta o leitor se ele estiver esperando espaço
    }

    private fun ensureStarted() {
        synchronized(startLock) {
            if (started) return
            started = true
            Thread(::feed, "channel-source-feeder").apply { isDaemon = true }.start()
        }
    }

    private fun feed() {
        try {
            val reader = openReader()
            M3uParser.scan(reader, baseUrl) { channel ->
                if (!put(channel)) throw Stop()
            }
            put(End)
        } catch (_: Stop) {
            // fechado: o scan já fechou o Reader
        } catch (t: Throwable) {
            put(Failure(t))
        }
    }

    /** Coloca [item] na fila esperando espaço; `false` se a fonte foi fechada enquanto esperava. */
    private fun put(item: Any): Boolean {
        while (!closed) {
            try {
                if (queue.offer(item, pollMs, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_POLL_MS = 50L
    }
}
