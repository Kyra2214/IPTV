package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Etapa 5: leitura em streaming da lista salva para o motor de teste. */
class ParserChannelSourceTest {

    private fun m3u(n: Int, from: Int = 0): String = buildString {
        append("#EXTM3U\n")
        for (i in from until from + n) {
            append("#EXTINF:-1 group-title=\"G${i % 3}\",Canal $i\n")
            append("http://example.com/$i.m3u8\n")
        }
    }

    private fun drain(source: ChannelSource): List<Channel> {
        val out = ArrayList<Channel>()
        while (true) out += source.next() ?: break
        return out
    }

    /** Reader que gera canais para sempre e avisa quando é fechado. */
    private class InfiniteReader : Reader() {
        val closed = AtomicBoolean(false)
        val charsServed = AtomicInteger()
        private var buf = "#EXTM3U\n"
        private var pos = 0
        private var n = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed.get()) throw IOException("fechado")
            if (pos >= buf.length) {
                buf = "#EXTINF:-1,Canal $n\nhttp://example.com/${n++}.m3u8\n"
                pos = 0
            }
            val k = minOf(len, buf.length - pos)
            for (i in 0 until k) cbuf[off + i] = buf[pos + i]
            pos += k
            charsServed.addAndGet(k)
            return k
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("tempo esgotado esperando: $what")
            Thread.sleep(5)
        }
    }

    // ---- leitura ------------------------------------------------------------

    @Test fun deliversAllChannelsInOrder() {
        val source = ParserChannelSource({ StringReader(m3u(10)) })
        val got = drain(source)
        assertEquals((0 until 10).map { "Canal $it" }, got.map { it.name })
        assertEquals("http://example.com/3.m3u8", got[3].streamUrl)
        assertEquals("G1", got[1].group)
    }

    @Test fun emptyListEndsAtOnceAndKeepsReturningNull() {
        val source = ParserChannelSource({ StringReader("#EXTM3U\n") })
        assertNull(source.next())
        assertNull(source.next())
    }

    @Test fun smallQueueStillDeliversALargeList() {
        val source = ParserChannelSource({ StringReader(m3u(20_000)) }, queueSize = 4)
        val got = drain(source)
        assertEquals(20_000, got.size)
        assertEquals("Canal 19999", got.last().name)
    }

    @Test fun repeatedUrlsAreDeliveredOnce() {
        val text = m3u(3) + "#EXTINF:-1,Repetido\nhttp://example.com/1.m3u8\n"
        assertEquals(3, drain(ParserChannelSource({ StringReader(text) })).size)
    }

    @Test fun relativeUrlsUseTheBase() {
        val text = "#EXTM3U\n#EXTINF:-1,Rel\nlive/1.m3u8\n"
        val ch = drain(ParserChannelSource({ StringReader(text) }, baseUrl = "http://host.example/lists/a.m3u")).single()
        assertEquals("http://host.example/lists/live/1.m3u8", ch.streamUrl)
    }

    @Test fun doesNotOpenTheReaderBeforeTheFirstNext() {
        val opened = AtomicBoolean(false)
        val source = ParserChannelSource({ opened.set(true); StringReader(m3u(1)) })
        Thread.sleep(100)
        assertFalse(opened.get())
        assertEquals(1, drain(source).size)
        assertTrue(opened.get())
    }

    // ---- fechar -------------------------------------------------------------

    @Test fun closeStopsTheFeederAndClosesTheReader() {
        val reader = InfiniteReader()
        val source = ParserChannelSource({ reader }, queueSize = 4)
        assertEquals("Canal 0", source.next()?.name)
        source.close()
        assertNull(source.next())
        waitUntil(what = "reader fechado") { reader.closed.get() }
    }

    @Test fun feederDoesNotReadFarAheadOfTheConsumer() {
        val reader = InfiniteReader()
        val source = ParserChannelSource({ reader }, queueSize = 4)
        source.next()
        Thread.sleep(300) // o leitor enche a fila e para
        val served = reader.charsServed.get()
        Thread.sleep(300)
        assertEquals("a leitura deveria ter parado com a fila cheia", served, reader.charsServed.get())
        source.close()
    }

    @Test fun closeBeforeStartNeverOpensTheReader() {
        val opened = AtomicBoolean(false)
        val source = ParserChannelSource({ opened.set(true); StringReader(m3u(1)) })
        source.close()
        assertNull(source.next())
        Thread.sleep(100)
        assertFalse(opened.get())
    }

    // ---- falhas -------------------------------------------------------------

    @Test fun readerThatCannotBeOpenedMakesNextThrow() {
        val source = ParserChannelSource({ throw IOException("sumiu") })
        assertThrows(IOException::class.java) { source.next() }
        assertNull(source.next()) // depois do erro a fonte está encerrada
    }

    @Test fun readFailureKeepsEarlierChannelsThenThrows() {
        val text = m3u(5)
        val failing = object : Reader() {
            private val inner = StringReader(text)
            private var served = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (served >= text.length) throw IOException("disco")
                val n = inner.read(cbuf, off, minOf(len, text.length - served))
                if (n > 0) served += n
                return n
            }
            override fun close() = inner.close()
        }
        val source = ParserChannelSource({ failing })
        val got = ArrayList<Channel>()
        assertThrows(IOException::class.java) { while (true) got += source.next() ?: break }
        assertEquals(5, got.size)
    }

    // ---- com o motor --------------------------------------------------------

    private class InstantProbe : StreamProbe {
        override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
            onDone(if (channel.name.endsWith("7")) ProbeSignal.HttpError(404) else ProbeSignal.Ready(1_000))
            return ProbeHandle { }
        }
    }

    @Test fun engineTestsAListReadFromTheParser() {
        val engine = StreamTestEngine(
            source = ParserChannelSource({ StringReader(m3u(30)) }, queueSize = 3),
            total = 30,
            probe = InstantProbe(),
            config = StreamTestConfig(concurrency = 3),
            progressIntervalMs = 0,
        )
        engine.start()
        assertTrue(engine.awaitFinished(10_000))
        assertEquals(EngineState.FINISHED, engine.state)
        assertFalse(engine.sourceFailed)
        assertEquals(30, engine.results().size)
        assertEquals(27, engine.workingChannels().size) // 7, 17 e 27 falham
        assertEquals(3, engine.results(TestFilter.HTTP_404).size)
    }

    @Test fun engineCancelClosesTheReader() {
        val reader = InfiniteReader()
        val never = object : StreamProbe {
            override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit) =
                ProbeHandle { onDone(ProbeSignal.Other) }
        }
        val engine = StreamTestEngine(
            source = ParserChannelSource({ reader }, queueSize = 4),
            total = 1_000_000,
            probe = never,
            config = StreamTestConfig(concurrency = 2),
            progressIntervalMs = 0,
        )
        engine.start()
        waitUntil(what = "testes ativos") { engine.peakConcurrency == 2 }
        engine.cancel()
        assertTrue(engine.awaitFinished(10_000))
        assertEquals(EngineState.CANCELLED, engine.state)
        waitUntil(what = "reader fechado") { reader.closed.get() }
    }

    @Test fun engineReportsSourceFailure() {
        val engine = StreamTestEngine(
            source = ParserChannelSource({ throw IOException("sumiu") }),
            total = 10,
            probe = InstantProbe(),
            progressIntervalMs = 0,
        )
        engine.start()
        assertTrue(engine.awaitFinished(10_000))
        assertTrue(engine.sourceFailed)
        assertEquals(0, engine.results().size)
    }
}
