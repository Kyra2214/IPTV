package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class EngineState {
    IDLE, RUNNING, PAUSED,

    /** Cancelamento pedido; esperando os testes ativos liberarem os recursos. */
    CANCELLING,
    FINISHED, CANCELLED;

    val isTerminal: Boolean get() = this == FINISHED || this == CANCELLED
}

/** Chamado na thread do motor; quem implementa deve ser rápido (a interface posta na thread principal). */
interface StreamTestListener {
    fun onProgress(progress: TestProgress) {}
    fun onStateChanged(state: EngineState) {}
}

/**
 * Motor do teste de lista (Kotlin puro). Executa os testes de uma [ChannelSource] com no máximo
 * [StreamTestConfig.concurrency] ao mesmo tempo, sem criar uma tarefa por canal:
 *
 * - **slots:** só puxa o próximo canal da fonte quando um slot libera;
 * - **uma thread de coordenação** guarda todo o estado (contadores, pausa, cancelamento), sem locks espalhados;
 * - **falha isolada:** um canal que falha (ou uma sonda que lança exceção) vira resultado e o teste segue;
 * - **dedup:** o mesmo [Channel.id] (hash da URL) é testado uma vez; a primeira entrada vence;
 * - **cancelar:** para de despachar, pede cancelamento aos ativos e espera todos reportarem
 *   ([cancelGraceMs] é o limite de espera); resultados já obtidos ficam; o que estava em andamento é descartado;
 * - **watchdog:** um teste que passa de [StreamTestConfig.hardTimeoutMs] é cancelado e contado como falha.
 *
 * Uso único: depois de FINISHED/CANCELLED o motor não roda de novo. Os métodos públicos podem ser
 * chamados de qualquer thread.
 */
class StreamTestEngine(
    private val source: ChannelSource,
    /** Total esperado (URLs únicas da análise); usado só no progresso. */
    private val total: Int,
    private val probe: StreamProbe,
    private val config: StreamTestConfig = StreamTestConfig(),
    private val listener: StreamTestListener? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Intervalo mínimo entre avisos de progresso (0 = a cada resultado). Início e fim sempre avisam. */
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val cancelGraceMs: Long = DEFAULT_CANCEL_GRACE_MS,
) {
    private class Active(val channel: Channel, val startedAt: Long, val attempt: Int, val cfg: StreamTestConfig) {
        var handle: ProbeHandle? = null
        var watchdog: ScheduledFuture<*>? = null
    }

    private val coordinator = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "stream-test-engine").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true // 100 mil watchdogs cancelados não podem ficar na fila
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    // Estado da thread de coordenação.
    private val active = HashMap<Long, Active>()
    private val seenIds = HashSet<String>()
    /** Canais que deram timeout e serão testados de novo (com prazos maiores) depois do resto da lista. */
    private val retryQueue = ArrayDeque<Channel>()
    private val retryConfig: StreamTestConfig by lazy { config.forRetry() }
    private var seq = 0L
    private var started = false
    private var paused = false
    private var cancelled = false
    private var sourceExhausted = false
    private var lastProgressAt = -1L

    // Compartilhado com outras threads (leitura): protegido por [lock].
    private val lock = Any()
    private val tally = TestTally()
    private val results = ArrayList<StreamTestResult>()
    private var skippedDuplicates = 0

    private val finishedLatch = CountDownLatch(1)

    @Volatile var state: EngineState = EngineState.IDLE
        private set

    /** A fonte lançou exceção no meio da leitura (a lista foi testada só até ali). */
    @Volatile var sourceFailed: Boolean = false
        private set

    /** Maior número de testes simultâneos observado (para conferir o limite). */
    @Volatile var peakConcurrency: Int = 0
        private set

    // ---- API pública --------------------------------------------------------

    fun start() = post {
        if (started) return@post
        started = true
        setState(EngineState.RUNNING)
        notifyProgress(force = true)
        fill()
    }

    fun pause() = post {
        if (state == EngineState.RUNNING) {
            paused = true
            setState(EngineState.PAUSED)
        }
    }

    fun resume() = post {
        if (state == EngineState.PAUSED) {
            paused = false
            setState(EngineState.RUNNING)
            fill()
        }
    }

    /** Para novos testes, cancela os ativos e mantém os resultados já obtidos. */
    fun cancel() = post {
        if (cancelled || state.isTerminal) return@post
        cancelled = true
        paused = false
        retryQueue.clear()
        setState(EngineState.CANCELLING)
        for (a in active.values) guarded { a.handle?.cancel() }
        guarded { source.close() }
        coordinator.schedule(Runnable { guarded { forceFinishCancelled() } }, cancelGraceMs, TimeUnit.MILLISECONDS)
        maybeFinish()
    }

    fun progress(): TestProgress = synchronized(lock) { tally.snapshot(effectiveTotal()) }

    /** Cópia dos resultados sob o [filter], na ordem em que terminaram. */
    fun results(filter: TestFilter = TestFilter.ALL): List<StreamTestResult> = synchronized(lock) {
        if (filter == TestFilter.ALL) ArrayList(results) else results.filter { it.outcome.matches(filter) }
    }

    /** Canais aprovados (a base de "Salvar funcionais"), na ordem em que terminaram. */
    fun workingChannels(): List<Channel> = synchronized(lock) {
        results.filter { it.isWorking }.map { it.channel }
    }

    val duplicatesSkipped: Int get() = synchronized(lock) { skippedDuplicates }

    /** Espera o fim (FINISHED ou CANCELLED). `true` se terminou dentro do prazo. */
    fun awaitFinished(timeoutMs: Long): Boolean = finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ---- coordenação (sempre na thread do motor) ----------------------------

    private fun fill() {
        while (!paused && !cancelled) {
            if (active.size >= config.concurrency) break
            if (!sourceExhausted) {
                val channel = try {
                    source.next()
                } catch (t: Throwable) {
                    sourceFailed = true
                    null
                }
                if (channel == null) {
                    sourceExhausted = true
                    continue // passa às tentativas extras, se houver
                }
                if (!seenIds.add(channel.id)) {
                    synchronized(lock) { skippedDuplicates++ }
                    continue
                }
                launch(channel, attempt = 0)
            } else {
                // Fim da lista: reconfere os que deram timeout, com prazos maiores.
                launch(retryQueue.removeFirstOrNull() ?: break, attempt = 1)
            }
        }
        maybeFinish()
    }

    private fun launch(channel: Channel, attempt: Int) {
        val token = ++seq
        val cfg = if (attempt == 0) config else retryConfig
        val a = Active(channel, clock(), attempt, cfg)
        active[token] = a
        if (active.size > peakConcurrency) peakConcurrency = active.size
        a.watchdog = coordinator.schedule(
            Runnable { guarded { onWatchdog(token) } },
            cfg.hardTimeoutMs,
            TimeUnit.MILLISECONDS,
        )
        try {
            a.handle = probe.start(channel, cfg) { signal -> post { complete(token, signal) } }
        } catch (t: Throwable) {
            // Sonda defeituosa: vira falha deste canal. Assíncrono de propósito (sem recursão em listas grandes).
            post { complete(token, ProbeSignal.Other) }
        }
    }

    private fun complete(token: Long, signal: ProbeSignal) {
        val a = active.remove(token) ?: return // resposta repetida ou tardia: ignorada
        a.watchdog?.cancel(false)
        settle(a, signal)
        notifyProgress(force = false)
        fill()
    }

    private fun onWatchdog(token: Long) {
        val a = active.remove(token) ?: return
        guarded { a.handle?.cancel() }
        settle(a, ProbeSignal.PrepareTimeout)
        notifyProgress(force = false)
        fill()
    }

    /** Registra o resultado ou, se foi só timeout e ainda há tentativa extra, devolve o canal para a fila. */
    private fun settle(a: Active, signal: ProbeSignal) {
        if (cancelled) return // depois de cancelar, o que estava em andamento é descartado
        if (a.attempt < config.timeoutRetries.coerceAtMost(1) && isRetryable(signal)) {
            retryQueue.addLast(a.channel)
        } else {
            record(a, signal)
        }
    }

    private fun isRetryable(signal: ProbeSignal): Boolean = when (signal) {
        ProbeSignal.ConnectTimeout, ProbeSignal.PrepareTimeout, ProbeSignal.ConfirmTimeout -> true
        is ProbeSignal.HttpError -> signal.status == 408 || signal.status == 429
        else -> false
    }

    private fun record(a: Active, signal: ProbeSignal) {
        val now = clock()
        val result = StreamTestClassifier.toResult(
            channel = a.channel,
            signal = signal,
            testedAt = now,
            elapsedMs = (now - a.startedAt).coerceAtLeast(0L),
        )
        synchronized(lock) {
            results += result
            tally.record(result.outcome)
        }
    }

    private fun maybeFinish() {
        if (state.isTerminal || active.isNotEmpty()) return
        when {
            cancelled -> finish(EngineState.CANCELLED)
            sourceExhausted && retryQueue.isEmpty() -> finish(EngineState.FINISHED)
        }
    }

    /** O limite de espera do cancelamento acabou e alguma sonda não respondeu: encerra mesmo assim. */
    private fun forceFinishCancelled() {
        if (state.isTerminal) return
        for (a in active.values) a.watchdog?.cancel(false)
        active.clear()
        finish(EngineState.CANCELLED)
    }

    private fun finish(end: EngineState) {
        state = end
        guarded { source.close() }
        notifyProgress(force = true)
        notifyState(end)
        coordinator.shutdown()
        finishedLatch.countDown()
    }

    private fun effectiveTotal(): Int = (total - skippedDuplicates).coerceAtLeast(0)

    // ---- avisos e utilitários -----------------------------------------------

    private fun setState(s: EngineState) {
        state = s
        notifyState(s)
    }

    private fun notifyState(s: EngineState) {
        val l = listener ?: return
        guarded { l.onStateChanged(s) }
    }

    private fun notifyProgress(force: Boolean) {
        val l = listener ?: return
        val now = clock()
        if (!force && progressIntervalMs > 0 && lastProgressAt >= 0 && now - lastProgressAt < progressIntervalMs) return
        lastProgressAt = now
        guarded { l.onProgress(progress()) }
    }

    private fun post(block: () -> Unit) {
        try {
            coordinator.execute { guarded(block) }
        } catch (_: RejectedExecutionException) {
            // motor já encerrado
        }
    }

    /** Nada que dê errado em uma sonda ou no listener pode derrubar o motor. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val DEFAULT_PROGRESS_INTERVAL_MS = 250L
        const val DEFAULT_CANCEL_GRACE_MS = 5_000L
    }
}
