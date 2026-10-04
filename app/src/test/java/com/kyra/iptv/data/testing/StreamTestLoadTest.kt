package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste de carga da LÓGICA (não reproduz streams reais nem mede RAM do aparelho): confere que
 * listas de 1.000, 10.000 e 100.000 canais são processadas de forma incremental, sem passar do
 * limite de testes simultâneos, sem criar threads por canal, com progresso e dedup corretos.
 */
class StreamTestLoadTest {

    private fun ch(i: Int) = Channel(
        id = "id$i", name = "Canal $i", streamUrl = "http://example.com/$i.m3u8", group = "G${i % 20}",
    )

    private fun indexOf(c: Channel) = c.id.removePrefix("id").toInt()

    private fun signalFor(i: Int): ProbeSignal = when {
        i % 5 == 0 -> ProbeSignal.HttpError(403)
        i % 7 == 0 -> ProbeSignal.ConnectTimeout
        else -> ProbeSignal.Ready(1_000)
    }

    private class Expected(n: Int) {
        var working = 0
        var forbidden = 0
        var timeout = 0

        init {
            for (i in 0 until n) when {
                i % 5 == 0 -> forbidden++
                i % 7 == 0 -> timeout++
                else -> working++
            }
        }
    }

    /** Gera os canais sob demanda (nunca a lista inteira) e conta quantos já foram entregues. */
    private fun generated(n: Int, produced: AtomicLong, duplicateEveryTenth: Boolean = false): ChannelSource {
        val seq = iterator<Channel> {
            for (i in 0 until n) {
                yield(ch(i))
                if (duplicateEveryTenth && i % 10 == 0) yield(ch(i))
            }
        }
        return IteratorChannelSource(object : Iterator<Channel> {
            override fun hasNext() = seq.hasNext()
            override fun next(): Channel = seq.next().also { produced.incrementAndGet() }
        })
    }

    /** Responde na hora e mede a "janela" (entregues − concluídos), as threads vivas e os starts. */
    private class MeasuringProbe(
        private val produced: AtomicLong,
        private val signalFor: (Int) -> ProbeSignal,
    ) : StreamProbe {
        val done = AtomicLong()
        val maxWindow = AtomicLong()
        val maxThreads = AtomicInteger()
        val starts = AtomicInteger()

        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            val n = starts.incrementAndGet()
            maxWindow.accumulateAndGet(produced.get() - done.get()) { a, b -> maxOf(a, b) }
            if (n % 1_000 == 0) maxThreads.accumulateAndGet(Thread.activeCount()) { a, b -> maxOf(a, b) }
            done.incrementAndGet()
            onDone(signalFor(channel.id.removePrefix("id").toInt()))
            return ProbeHandle { }
        }
    }

    private fun runSync(n: Int, concurrency: Int = 4) {
        val baselineThreads = Thread.activeCount()
        val produced = AtomicLong()
        val probe = MeasuringProbe(produced, ::signalFor)
        val e = StreamTestEngine(
            source = generated(n, produced), total = n, probe = probe,
            config = StreamTestConfig(concurrency = concurrency), progressIntervalMs = 0L,
        )
        e.start()
        assertTrue("n=$n não terminou", e.awaitFinished(120_000))

        val exp = Expected(n)
        val p = e.progress()
        assertEquals(EngineState.FINISHED, e.state)
        assertEquals(n, p.total)
        assertEquals(n, p.tested)
        assertEquals(100, p.percent)
        assertEquals(exp.working, p.working)
        assertEquals(exp.forbidden, p.count(TestFilter.HTTP_403))
        assertEquals(exp.timeout, p.count(TestFilter.TIMEOUT))
        assertEquals(n, e.results().size)
        assertEquals(exp.working, e.workingChannels().size)
        assertEquals(n, probe.starts.get())

        // Incremental: nunca houve mais que `concurrency` canais entregues e ainda não concluídos.
        assertTrue("janela ${probe.maxWindow.get()}", probe.maxWindow.get() <= concurrency)
        assertTrue("pico ${e.peakConcurrency}", e.peakConcurrency <= concurrency)
        // Sem uma thread por canal: só a thread do motor a mais (com folga para a JVM de teste).
        assertTrue(
            "threads ${probe.maxThreads.get()} (base $baselineThreads)",
            probe.maxThreads.get() - baselineThreads <= 10,
        )
    }

    @Test fun oneThousandChannels() = runSync(1_000)

    @Test fun tenThousandChannels() = runSync(10_000)

    @Test fun oneHundredThousandChannels() = runSync(100_000)

    @Test fun oneHundredThousandChannelsWithEightSlots() = runSync(100_000, concurrency = 8)

    @Test fun duplicatesInALargeListAreTestedOnce() {
        val n = 20_000
        val produced = AtomicLong()
        val probe = MeasuringProbe(produced, ::signalFor)
        // Uma repetida a cada 10 canais (i = 0, 10, 20...): 2.000 repetições.
        val dups = (0 until n).count { it % 10 == 0 }
        val e = StreamTestEngine(
            source = generated(n, produced, duplicateEveryTenth = true), total = n + dups, probe = probe,
            config = StreamTestConfig(concurrency = 4), progressIntervalMs = 0L,
        )
        e.start()
        assertTrue(e.awaitFinished(120_000))

        assertEquals(n, probe.starts.get())
        assertEquals(n, e.results().size)
        assertEquals(n, e.results().map { it.channel.id }.toSet().size)
        assertEquals(dups, e.duplicatesSkipped)
        val p = e.progress()
        assertEquals(n, p.total) // as repetidas saem do total
        assertEquals(100, p.percent)
    }

    @Test fun asynchronousProbesStayWithinTheLimitOnLargeLists() {
        val scheduler = Executors.newScheduledThreadPool(2)
        try {
            val current = AtomicInteger()
            val peak = AtomicInteger()
            val probe = object : StreamProbe {
                override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
                    peak.accumulateAndGet(current.incrementAndGet()) { a, b -> maxOf(a, b) }
                    val finished = AtomicBoolean(false)
                    val finish = {
                        if (finished.compareAndSet(false, true)) {
                            current.decrementAndGet()
                            onDone(signalFor(indexOf(channel)))
                        }
                    }
                    val f = scheduler.schedule(Runnable { finish() }, 1, TimeUnit.MILLISECONDS)
                    return ProbeHandle { f.cancel(false); finish() }
                }
            }
            val n = 3_000
            val e = StreamTestEngine(
                source = generated(n, AtomicLong()), total = n, probe = probe,
                config = StreamTestConfig(concurrency = 8), progressIntervalMs = 0L,
            )
            e.start()
            assertTrue(e.awaitFinished(120_000))

            val exp = Expected(n)
            assertEquals(n, e.progress().tested)
            assertEquals(exp.working, e.progress().working)
            assertTrue("pico ${peak.get()}", peak.get() <= 8)
            assertTrue(peak.get() >= 2)
            assertTrue(e.peakConcurrency <= 8)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun cancelingALargeRunKeepsPartialResultsAndStopsPulling() {
        val n = 100_000
        val produced = AtomicLong()
        val gate = AtomicInteger()
        val probe = object : StreamProbe {
            override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
                gate.incrementAndGet()
                onDone(ProbeSignal.Ready(1_000))
                return ProbeHandle { }
            }
        }
        val e = StreamTestEngine(
            source = generated(n, produced), total = n, probe = probe,
            config = StreamTestConfig(concurrency = 4), progressIntervalMs = 0L,
        )
        e.start()
        val end = System.currentTimeMillis() + 30_000
        while (e.progress().tested < 500 && System.currentTimeMillis() < end) Thread.sleep(1)
        e.cancel()
        assertTrue(e.awaitFinished(30_000))

        val tested = e.progress().tested
        assertEquals(EngineState.CANCELLED, e.state)
        assertTrue("testou $tested", tested in 500 until n)
        assertEquals(tested, e.workingChannels().size)
        // Parou de puxar canais: sobra quase tudo da lista sem ser lido.
        assertTrue("lidos ${produced.get()}", produced.get() < n)
    }
}
