package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.data.parser.ParseStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTestModelsTest {

    // ---- resultados e filtros ----------------------------------------------

    @Test fun onlyWorkingIsWorkingAndHasNoFailureBucket() {
        for (o in TestOutcome.values()) {
            assertEquals(o == TestOutcome.WORKING, o.isWorking)
            assertEquals(o == TestOutcome.WORKING, o.bucket == null)
        }
    }

    @Test fun specificHttpStatusesHaveTheirOwnFilter() {
        assertEquals(TestFilter.HTTP_403, TestOutcome.HTTP_403.bucket)
        assertEquals(TestFilter.HTTP_404, TestOutcome.HTTP_404.bucket)
        assertEquals(TestFilter.HTTP_411, TestOutcome.HTTP_411.bucket)
        assertEquals(TestFilter.HTTP_5XX, TestOutcome.HTTP_5XX.bucket)
    }

    @Test fun timeoutsAnd408ShareTheTimeoutFilter() {
        listOf(
            TestOutcome.HTTP_408, TestOutcome.CONNECT_TIMEOUT,
            TestOutcome.PREPARE_TIMEOUT, TestOutcome.CONFIRM_TIMEOUT,
        ).forEach { assertEquals(it.name, TestFilter.TIMEOUT, it.bucket) }
    }

    @Test fun everythingElseFallsIntoOthers() {
        listOf(
            TestOutcome.HTTP_401, TestOutcome.HTTP_429, TestOutcome.HTTP_OTHER, TestOutcome.DNS,
            TestOutcome.NETWORK, TestOutcome.FORMAT, TestOutcome.DECODER, TestOutcome.INCOMPATIBLE,
            TestOutcome.CLEARTEXT, TestOutcome.OTHER,
        ).forEach { assertEquals(it.name, TestFilter.OTHER, it.bucket) }
    }

    @Test fun everyFailureMatchesExactlyOneBucketFilter() {
        val bucketFilters = TestFilter.values().filter {
            it != TestFilter.ALL && it != TestFilter.WORKING && it != TestFilter.FAILING
        }
        for (o in TestOutcome.values()) {
            val hits = bucketFilters.count { o.matches(it) }
            assertEquals(o.name, if (o.isWorking) 0 else 1, hits)
        }
    }

    @Test fun allWorkingAndFailingFiltersPartitionTheOutcomes() {
        for (o in TestOutcome.values()) {
            assertTrue(o.matches(TestFilter.ALL))
            assertEquals(o.isWorking, o.matches(TestFilter.WORKING))
            assertEquals(!o.isWorking, o.matches(TestFilter.FAILING))
        }
    }

    @Test fun labelsNeverLookLikeUrls() {
        for (o in TestOutcome.values()) {
            assertFalse(o.name, o.label.contains("://"))
            assertTrue(o.name, o.label.isNotBlank())
        }
    }

    @Test fun resultKeepsChannelMetadataUntouched() {
        val ch = Channel(
            id = "abc", name = "Canal", streamUrl = "http://example.com/a.m3u8", group = "Esportes",
            tvgId = "tv1", tvgName = "Canal HD", logoUrl = "http://example.com/l.png",
            headers = mapOf("User-Agent" to "x"),
        )
        val r = StreamTestResult(ch, TestOutcome.HTTP_403, 403, "403 Forbidden", testedAt = 10L, elapsedMs = 25L)
        assertEquals(ch, r.channel)
        assertEquals("abc", r.channel.id)
        assertFalse(r.isWorking)
        assertEquals(403, r.httpStatus)
    }

    // ---- configuração ------------------------------------------------------

    @Test fun defaultConfigMatchesThePlan() {
        val c = StreamTestConfig()
        assertEquals(4, c.concurrency)
        assertEquals(8_000, c.connectTimeoutMs)
        assertEquals(8_000, c.readTimeoutMs)
        assertEquals(15_000L, c.prepareTimeoutMs)
        assertEquals(5_000L, c.confirmTimeoutMs)
        assertEquals(500L, c.confirmBufferedMs)
        assertEquals(64, c.feederQueueSize)
    }

    @Test fun hardTimeoutIsTheSumOfTimeoutsPlusMargin() {
        val c = StreamTestConfig()
        assertEquals(8_000L + 15_000L + 5_000L + 5_000L, c.hardTimeoutMs)
        val tiny = StreamTestConfig(
            connectTimeoutMs = 10, prepareTimeoutMs = 10, confirmTimeoutMs = 10, watchdogMarginMs = 0,
        )
        assertEquals(30L, tiny.hardTimeoutMs)
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(watchdogMarginMs = -1) }
    }

    @Test fun concurrencyIsLimitedToOneThroughEight() {
        assertEquals(1, StreamTestConfig(concurrency = 1).concurrency)
        assertEquals(8, StreamTestConfig().copy(concurrency = 8).concurrency)
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(concurrency = 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(concurrency = 9) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig().copy(concurrency = 1000) }
    }

    @Test fun invalidTimeoutsAndQueueAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(connectTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(readTimeoutMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(prepareTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(confirmTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(confirmBufferedMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { StreamTestConfig(feederQueueSize = 0) }
        assertEquals(0L, StreamTestConfig(confirmBufferedMs = 0).confirmBufferedMs)
    }

    // ---- análise da lista --------------------------------------------------

    @Test fun analysisFromParseStats() {
        val stats = ParseStats(
            count = 1731, skipped = 4, duplicates = 111, hlsStreamTags = false, iptvAttributes = true,
        )
        val a = ListAnalysis.from(stats, groups = 23)
        assertEquals(1846, a.totalEntries)
        assertEquals(1731, a.uniqueUrls)
        assertEquals(111, a.duplicates)
        assertEquals(4, a.invalid)
        assertEquals(23, a.groups)
        assertEquals(115, a.discardedByParser)
    }

    // ---- contadores e progresso -------------------------------------------

    @Test fun emptyTallyHasZeroProgress() {
        val p = TestTally().snapshot(total = 0)
        assertEquals(0, p.tested)
        assertEquals(0, p.percent)
        assertFalse(p.finished)
    }

    @Test fun tallyCountsByOutcomeAndFilter() {
        val t = TestTally()
        repeat(3) { t.record(TestOutcome.WORKING) }
        repeat(2) { t.record(TestOutcome.HTTP_403) }
        t.record(TestOutcome.HTTP_404)
        t.record(TestOutcome.CONNECT_TIMEOUT)
        t.record(TestOutcome.HTTP_408)
        t.record(TestOutcome.HTTP_429)
        t.record(TestOutcome.DNS)

        assertEquals(10, t.tested)
        assertEquals(3, t.working)
        assertEquals(2, t.count(TestOutcome.HTTP_403))
        assertEquals(10, t.count(TestFilter.ALL))
        assertEquals(3, t.count(TestFilter.WORKING))
        assertEquals(7, t.count(TestFilter.FAILING))
        assertEquals(2, t.count(TestFilter.HTTP_403))
        assertEquals(1, t.count(TestFilter.HTTP_404))
        assertEquals(0, t.count(TestFilter.HTTP_411))
        assertEquals(2, t.count(TestFilter.TIMEOUT))
        assertEquals(0, t.count(TestFilter.HTTP_5XX))
        assertEquals(2, t.count(TestFilter.OTHER))
    }

    @Test fun snapshotReportsPercentAndCounts() {
        val t = TestTally()
        repeat(183) { t.record(TestOutcome.WORKING) }
        repeat(159) { t.record(TestOutcome.HTTP_404) }
        val p = t.snapshot(total = 1842)
        assertEquals(342, p.tested)
        assertEquals(183, p.working)
        assertEquals(159, p.failed)
        assertEquals(18, p.percent)
        assertFalse(p.finished)
        assertEquals(159, p.count(TestFilter.HTTP_404))
        assertEquals(342, p.count(TestFilter.ALL))
    }

    @Test fun snapshotIsImmutableAfterMoreResults() {
        val t = TestTally()
        t.record(TestOutcome.WORKING)
        val before = t.snapshot(total = 2)
        t.record(TestOutcome.HTTP_403)
        assertEquals(1, before.tested)
        assertEquals(0, before.count(TestFilter.HTTP_403))
        assertEquals(2, t.snapshot(total = 2).tested)
        assertTrue(t.snapshot(total = 2).finished)
    }

    @Test fun percentIsClampedAndHandlesLargeLists() {
        val t = TestTally()
        repeat(5) { t.record(TestOutcome.WORKING) }
        assertEquals(100, t.snapshot(total = 3).percent) // nunca passa de 100
        assertEquals(0, TestTally().snapshot(total = 100_000).percent)
    }
}
