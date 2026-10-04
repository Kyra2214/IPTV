package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTestSessionTest {

    private fun ch(i: Int) = Channel("id$i", "Canal $i", "http://example.com/$i.m3u8")

    private class InstantProbe(private val failOdd: Boolean = true) : StreamProbe {
        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            val odd = channel.id.removePrefix("id").toInt() % 2 == 1
            onDone(if (failOdd && odd) ProbeSignal.HttpError(403) else ProbeSignal.Ready(1_000))
            return ProbeHandle { }
        }
    }

    private fun session(n: Int, probe: StreamProbe = InstantProbe(), open: () -> ChannelSource = { IteratorChannelSource((0 until n).map { ch(it) }.iterator()) }) =
        StreamTestSession("p1", "Minha lista", ListAnalysis(n, n, 0, 0, 3), open, probe)

    @Test fun beforeStartIsIdleWithZeroProgress() {
        val s = session(10)
        assertEquals(EngineState.IDLE, s.state)
        assertFalse(s.started)
        val p = s.progress()
        assertEquals(10, p.total)
        assertEquals(0, p.tested)
        assertTrue(s.results().isEmpty())
        assertTrue(s.workingChannels().isEmpty())
    }

    @Test fun runsToTheEndAndExposesResultsAndWorkingChannels() {
        val s = session(10)
        assertTrue(s.start(StreamTestConfig(concurrency = 2)))
        val end = System.currentTimeMillis() + 10_000
        while (s.state != EngineState.FINISHED && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(EngineState.FINISHED, s.state)
        assertEquals(10, s.results().size)
        assertEquals(5, s.results(TestFilter.HTTP_403).size)
        assertEquals(listOf("id0", "id2", "id4", "id6", "id8"), s.workingChannels().map { it.id }.sorted())
        assertEquals(100, s.progress().percent)
    }

    @Test fun startOnlyWorksOnce() {
        val s = session(3)
        assertTrue(s.start())
        assertFalse(s.start())
    }

    @Test fun failingToOpenTheSourceLeavesTheSessionNotStarted() {
        var fail = true
        val s = session(3, open = {
            if (fail) throw IllegalStateException("lista apagada")
            IteratorChannelSource(listOf(ch(0)).iterator())
        })
        assertThrows(IllegalStateException::class.java) { s.start() }
        assertFalse(s.started)
        assertEquals(EngineState.IDLE, s.state)
        fail = false
        assertTrue(s.start())
    }

    @Test fun listenersGetStateChangesAndCanBeRemoved() {
        val s = session(4)
        val states = CopyOnWriteArrayList<EngineState>()
        val l = object : StreamTestListener {
            override fun onStateChanged(state: EngineState) { states += state }
        }
        s.addListener(l)
        s.start()
        val end = System.currentTimeMillis() + 10_000
        while (s.state != EngineState.FINISHED && System.currentTimeMillis() < end) Thread.sleep(5)
        val deadline = System.currentTimeMillis() + 2_000
        while (EngineState.FINISHED !in states && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(EngineState.RUNNING in states)
        assertTrue(EngineState.FINISHED in states)

        val s2 = session(2)
        val calls = CopyOnWriteArrayList<EngineState>()
        val l2 = object : StreamTestListener {
            override fun onStateChanged(state: EngineState) { calls += state }
        }
        s2.addListener(l2)
        s2.removeListener(l2)
        s2.start()
        Thread.sleep(200)
        assertTrue(calls.isEmpty())
    }

    @Test fun aListenerThatThrowsDoesNotBreakTheTest() {
        val s = session(4)
        s.addListener(object : StreamTestListener {
            override fun onProgress(progress: TestProgress) { throw IllegalStateException("tela quebrada") }
            override fun onStateChanged(state: EngineState) { throw IllegalStateException("tela quebrada") }
        })
        s.start()
        val end = System.currentTimeMillis() + 10_000
        while (s.state != EngineState.FINISHED && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(EngineState.FINISHED, s.state)
        assertEquals(4, s.results().size)
    }

    @Test fun cancelBeforeStartDoesNothing() {
        val s = session(3)
        s.cancel()
        s.pause()
        s.resume()
        assertEquals(EngineState.IDLE, s.state)
    }
}
