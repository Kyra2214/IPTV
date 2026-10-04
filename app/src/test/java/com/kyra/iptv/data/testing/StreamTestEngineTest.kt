package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTestEngineTest {

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    @After fun tearDown() {
        scheduler.shutdownNow()
    }

    // ---- ajudantes ----------------------------------------------------------

    private fun ch(i: Int) = Channel(
        id = "id$i", name = "Canal $i", streamUrl = "http://example.com/$i.m3u8", group = "G${i % 3}",
        tvgId = "tvg$i", tvgName = "Canal $i HD", logoUrl = "http://example.com/$i.png",
        headers = mapOf("User-Agent" to "ua$i"),
    )

    private fun channels(n: Int) = (0 until n).map { ch(it) }

    private class TrackingSource(private val items: List<Channel>, private val failAt: Int = -1) : ChannelSource {
        private var i = 0
        @Volatile var closed = false

        override fun next(): Channel? {
            if (closed || i >= items.size) return null
            if (i == failAt) throw IllegalStateException("falha de leitura")
            return items[i++]
        }

        override fun close() {
            closed = true
        }
    }

    /** Sonda que responde na hora, na própria thread de quem chamou. */
    private class InstantProbe(private val signalFor: (Channel) -> ProbeSignal) : StreamProbe {
        val started = AtomicInteger()
        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            started.incrementAndGet()
            onDone(signalFor(channel))
            return ProbeHandle { }
        }
    }

    /** Sonda que só termina quando o teste manda. */
    private class ManualProbe : StreamProbe {
        class Pending(val channel: Channel, val onDone: (ProbeSignal) -> Unit) {
            val done = AtomicBoolean(false)
        }

        val pending = CopyOnWriteArrayList<Pending>()
        val started = AtomicInteger()
        val cancelCount = AtomicInteger()
        @Volatile var ignoreCancel = false

        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            val p = Pending(channel, onDone)
            pending.add(p)
            started.incrementAndGet()
            return ProbeHandle {
                cancelCount.incrementAndGet()
                if (!ignoreCancel) finish(p, ProbeSignal.Other)
            }
        }

        fun finish(p: Pending, signal: ProbeSignal) {
            if (p.done.compareAndSet(false, true)) {
                pending.remove(p)
                p.onDone(signal)
            }
        }

        fun completeFirst(signal: ProbeSignal): Boolean {
            val p = pending.firstOrNull { !it.done.get() } ?: return false
            finish(p, signal)
            return true
        }
    }

    /** Sonda que termina depois de [delayMs], em outra thread; registra o pico de testes simultâneos. */
    private inner class DelayedProbe(private val delayMs: Long) : StreamProbe {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            val now = current.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            val done = AtomicBoolean(false)
            val finish = {
                if (done.compareAndSet(false, true)) {
                    current.decrementAndGet()
                    onDone(ProbeSignal.Ready(1_000))
                }
            }
            val future = scheduler.schedule(Runnable { finish() }, delayMs, TimeUnit.MILLISECONDS)
            return ProbeHandle { future.cancel(false); finish() }
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: String = "condição", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("tempo esgotado esperando: $what")
            Thread.sleep(5)
        }
    }

    private fun engine(
        source: ChannelSource,
        total: Int,
        probe: StreamProbe,
        config: StreamTestConfig = StreamTestConfig(),
        listener: StreamTestListener? = null,
        clock: () -> Long = System::currentTimeMillis,
        progressIntervalMs: Long = 0L,
        cancelGraceMs: Long = 2_000L,
    ) = StreamTestEngine(
        source = source, total = total, probe = probe, config = config, listener = listener,
        clock = clock, progressIntervalMs = progressIntervalMs, cancelGraceMs = cancelGraceMs,
    )

    private val ready = ProbeSignal.Ready(1_000)

    // ---- fluxo normal -------------------------------------------------------

    @Test fun testsEveryChannelAndCountsProgress() {
        val probe = InstantProbe { c -> if (c.id.removePrefix("id").toInt() % 2 == 0) ready else ProbeSignal.HttpError(404) }
        val e = engine(TrackingSource(channels(20)), 20, probe)
        e.start()
        assertTrue(e.awaitFinished(5_000))

        val p = e.progress()
        assertEquals(EngineState.FINISHED, e.state)
        assertEquals(20, p.total)
        assertEquals(20, p.tested)
        assertEquals(10, p.working)
        assertEquals(10, p.failed)
        assertEquals(100, p.percent)
        assertTrue(p.finished)
        assertEquals(10, p.count(TestFilter.HTTP_404))
        assertEquals(20, e.results().size)
        assertEquals(10, e.results(TestFilter.WORKING).size)
        assertEquals(10, e.results(TestFilter.FAILING).size)
        assertEquals(10, e.workingChannels().size)
    }

    @Test fun withOneSlotResultsKeepSourceOrder() {
        val e = engine(TrackingSource(channels(30)), 30, InstantProbe { ready }, StreamTestConfig(concurrency = 1))
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertEquals((0 until 30).map { "id$it" }, e.results().map { it.channel.id })
        assertEquals(1, e.peakConcurrency)
    }

    @Test fun resultsKeepTheChannelMetadataAndFillTimes() {
        val time = AtomicLong(1_000)
        val original = ch(7)
        val e = engine(
            TrackingSource(listOf(original)), 1, InstantProbe { ready },
            clock = { time.addAndGet(10) },
        )
        e.start()
        assertTrue(e.awaitFinished(5_000))
        val r = e.results().single()
        assertSame(original, r.channel) // mesmo objeto: id, grupo, tvg, logo e headers intactos
        assertEquals("id7", r.channel.id)
        assertTrue(r.testedAt > 1_000)
        assertTrue(r.elapsedMs >= 0)
        assertEquals(TestOutcome.WORKING, r.outcome)
    }

    // ---- concorrência -------------------------------------------------------

    @Test fun fillsExactlyTheConfiguredNumberOfSlots() {
        val probe = ManualProbe()
        val e = engine(TrackingSource(channels(10)), 10, probe, StreamTestConfig(concurrency = 3))
        e.start()
        waitUntil(what = "3 testes ativos") { probe.pending.size == 3 }
        Thread.sleep(80) // dá tempo de um bug abrir mais que 3
        assertEquals(3, probe.pending.size)
        assertEquals(3, probe.started.get())

        probe.completeFirst(ready)
        waitUntil(what = "o slot liberado ser reusado") { probe.started.get() == 4 && probe.pending.size == 3 }
        e.cancel()
        assertTrue(e.awaitFinished(5_000))
    }

    @Test fun neverRunsMoreThanTheLimitAtOnce() {
        val probe = DelayedProbe(delayMs = 2)
        val e = engine(TrackingSource(channels(300)), 300, probe, StreamTestConfig(concurrency = 4))
        e.start()
        assertTrue(e.awaitFinished(30_000))
        assertEquals(300, e.progress().tested)
        assertTrue("pico ${probe.peak.get()}", probe.peak.get() <= 4)
        assertTrue(probe.peak.get() >= 2) // houve paralelismo de fato
        assertTrue(e.peakConcurrency <= 4)
    }

    // ---- falhas isoladas ----------------------------------------------------

    @Test fun oneChannelThatBlowsUpDoesNotStopTheRest() {
        val probe = object : StreamProbe {
            override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
                if (channel.id == "id3") throw IllegalStateException("sonda quebrada")
                onDone(ready)
                return ProbeHandle { }
            }
        }
        val e = engine(TrackingSource(channels(10)), 10, probe)
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(10, e.progress().tested)
        assertEquals(9, e.progress().working)
        assertEquals(TestOutcome.OTHER, e.results().first { it.channel.id == "id3" }.outcome)
    }

    @Test fun aProbeThatAlwaysThrowsDoesNotOverflowTheStack() {
        val probe = object : StreamProbe {
            override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle =
                throw IllegalStateException("sempre")
        }
        val e = engine(TrackingSource(channels(5_000)), 5_000, probe)
        e.start()
        assertTrue(e.awaitFinished(30_000))
        assertEquals(5_000, e.progress().tested)
        assertEquals(5_000, e.progress().count(TestFilter.OTHER))
    }

    @Test fun repeatedCompletionsAreIgnored() {
        val probe = object : StreamProbe {
            override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
                onDone(ready)
                onDone(ProbeSignal.HttpError(500))
                return ProbeHandle { }
            }
        }
        val e = engine(TrackingSource(channels(8)), 8, probe)
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(8, e.results().size)
        assertTrue(e.results().all { it.outcome == TestOutcome.WORKING })
    }

    @Test fun aStuckProbeIsCutByTheWatchdog() {
        val probe = ManualProbe()
        val cfg = StreamTestConfig(
            concurrency = 2, connectTimeoutMs = 10, prepareTimeoutMs = 10, confirmTimeoutMs = 10, watchdogMarginMs = 0,
        )
        val e = engine(TrackingSource(channels(3)), 3, probe, cfg)
        e.start()
        assertTrue(e.awaitFinished(10_000))
        assertEquals(EngineState.FINISHED, e.state)
        assertEquals(3, e.progress().tested)
        assertTrue(e.results().all { it.outcome == TestOutcome.PREPARE_TIMEOUT })
        assertEquals(3, probe.cancelCount.get())
    }

    @Test fun sourceFailureEndsTheRunKeepingResults() {
        val e = engine(TrackingSource(channels(20), failAt = 5), 20, InstantProbe { ready })
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(EngineState.FINISHED, e.state)
        assertTrue(e.sourceFailed)
        assertEquals(5, e.progress().tested)
    }

    // ---- deduplicação -------------------------------------------------------

    @Test fun sameIdIsTestedOnceAndFirstEntryWins() {
        val first = ch(1).copy(name = "Primeiro")
        val dup = ch(1).copy(name = "Repetido")
        val items = listOf(ch(0), first, dup, ch(2), ch(0), ch(3))
        val probe = InstantProbe { ready }
        val e = engine(TrackingSource(items), total = 6, probe = probe)
        e.start()
        assertTrue(e.awaitFinished(5_000))

        assertEquals(4, probe.started.get())
        assertEquals(4, e.results().size)
        assertEquals(2, e.duplicatesSkipped)
        assertEquals("Primeiro", e.results().first { it.channel.id == "id1" }.channel.name)
        val p = e.progress()
        assertEquals(4, p.total) // as repetidas saem do total
        assertEquals(100, p.percent)
    }

    // ---- pausa --------------------------------------------------------------

    @Test fun pauseStopsDispatchingAndResumeContinues() {
        val probe = ManualProbe()
        val e = engine(TrackingSource(channels(10)), 10, probe, StreamTestConfig(concurrency = 2))
        e.start()
        waitUntil(what = "2 ativos") { probe.pending.size == 2 }

        e.pause()
        waitUntil(what = "estado pausado") { e.state == EngineState.PAUSED }
        probe.completeFirst(ready)
        probe.completeFirst(ready)
        waitUntil(what = "2 resultados") { e.progress().tested == 2 }
        Thread.sleep(80)
        assertEquals(0, probe.pending.size) // pausado: nada novo começou
        assertEquals(2, probe.started.get())
        assertEquals(EngineState.PAUSED, e.state)

        e.resume()
        waitUntil(what = "retomar") { probe.pending.size == 2 }
        assertEquals(EngineState.RUNNING, e.state)

        val end = System.currentTimeMillis() + 10_000
        while (e.state != EngineState.FINISHED) {
            if (System.currentTimeMillis() > end) throw AssertionError("não terminou depois de retomar")
            probe.completeFirst(ready)
            Thread.sleep(2)
        }
        assertEquals(10, e.progress().tested)
    }

    // ---- cancelamento -------------------------------------------------------

    @Test fun cancelKeepsFinishedResultsAndDiscardsRunningOnes() {
        val probe = ManualProbe()
        val source = TrackingSource(channels(10))
        val e = engine(source, 10, probe, StreamTestConfig(concurrency = 2))
        e.start()
        waitUntil(what = "2 ativos") { probe.pending.size == 2 }
        probe.completeFirst(ready)
        waitUntil(what = "terceiro teste") { probe.started.get() == 3 && probe.pending.size == 2 }

        e.cancel()
        assertTrue(e.awaitFinished(5_000))

        assertEquals(EngineState.CANCELLED, e.state)
        assertEquals(1, e.progress().tested) // os 2 em andamento foram descartados, não contados como falha
        assertEquals(1, e.results().size)
        assertEquals(1, e.workingChannels().size)
        assertEquals(2, probe.cancelCount.get())
        assertEquals(3, probe.started.get()) // nenhum teste novo depois de cancelar
        assertTrue(source.closed)
    }

    @Test fun cancelBeforeStartTestsNothing() {
        val probe = InstantProbe { ready }
        val source = TrackingSource(channels(5))
        val e = engine(source, 5, probe)
        e.cancel()
        assertTrue(e.awaitFinished(5_000))
        e.start() // depois de encerrado, ignorado
        assertEquals(EngineState.CANCELLED, e.state)
        assertEquals(0, probe.started.get())
        assertEquals(0, e.progress().tested)
        assertTrue(source.closed)
    }

    @Test fun cancelEndsEvenIfAProbeNeverAnswers() {
        val probe = ManualProbe().also { it.ignoreCancel = true }
        val e = engine(TrackingSource(channels(4)), 4, probe, StreamTestConfig(concurrency = 2), cancelGraceMs = 100)
        e.start()
        waitUntil(what = "2 ativos") { probe.pending.size == 2 }
        e.cancel()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(EngineState.CANCELLED, e.state)
        assertEquals(0, e.progress().tested)
        assertEquals(2, probe.cancelCount.get())
    }

    @Test fun cancelWhilePausedStillEnds() {
        val probe = ManualProbe()
        val e = engine(TrackingSource(channels(6)), 6, probe, StreamTestConfig(concurrency = 2))
        e.start()
        waitUntil(what = "2 ativos") { probe.pending.size == 2 }
        e.pause()
        waitUntil(what = "pausado") { e.state == EngineState.PAUSED }
        e.cancel()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(EngineState.CANCELLED, e.state)
    }

    // ---- avisos -------------------------------------------------------------

    @Test fun listenerSeesStatesAndFinalProgressEvenIfItThrows() {
        val states = CopyOnWriteArrayList<EngineState>()
        val progresses = CopyOnWriteArrayList<TestProgress>()
        val listener = object : StreamTestListener {
            override fun onProgress(progress: TestProgress) {
                progresses.add(progress)
                throw RuntimeException("bug na tela")
            }

            override fun onStateChanged(state: EngineState) {
                states.add(state)
            }
        }
        val e = engine(TrackingSource(channels(12)), 12, InstantProbe { ready }, listener = listener)
        e.start()
        assertTrue(e.awaitFinished(5_000))

        assertTrue(states.contains(EngineState.RUNNING))
        assertEquals(EngineState.FINISHED, states.last())
        assertEquals(12, progresses.last().tested)
        assertEquals(12, e.progress().tested)
    }

    @Test fun progressNotificationsAreThrottled() {
        val count = AtomicInteger()
        val last = AtomicInteger(-1)
        val listener = object : StreamTestListener {
            override fun onProgress(progress: TestProgress) {
                count.incrementAndGet()
                last.set(progress.tested)
            }
        }
        // Relógio parado e intervalo enorme: só o aviso inicial e o final passam.
        val e = engine(
            TrackingSource(channels(50)), 50, InstantProbe { ready },
            listener = listener, clock = { 5_000L }, progressIntervalMs = 3_600_000L,
        )
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertTrue("avisos: ${count.get()}", count.get() <= 3)
        assertEquals(50, last.get())
    }

    @Test fun resultsAreFilterableByBucket() {
        val probe = InstantProbe { c ->
            when (c.id.removePrefix("id").toInt() % 4) {
                0 -> ready
                1 -> ProbeSignal.HttpError(403)
                2 -> ProbeSignal.HttpError(503)
                else -> ProbeSignal.ConnectTimeout
            }
        }
        val e = engine(TrackingSource(channels(40)), 40, probe)
        e.start()
        assertTrue(e.awaitFinished(5_000))
        assertEquals(10, e.results(TestFilter.WORKING).size)
        assertEquals(10, e.results(TestFilter.HTTP_403).size)
        assertEquals(10, e.results(TestFilter.HTTP_5XX).size)
        assertEquals(10, e.results(TestFilter.TIMEOUT).size)
        assertEquals(0, e.results(TestFilter.HTTP_404).size)
        assertEquals(30, e.results(TestFilter.FAILING).size)
        assertEquals(10, e.progress().count(TestFilter.HTTP_403))
        assertNotNull(e.results().firstOrNull())
        assertFalse(e.results().any { it.reason.contains("://") })
    }
}
