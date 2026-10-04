package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.player.FailureAction
import com.kyra.iptv.player.FailureKind
import com.kyra.iptv.player.PlaybackErrorPolicy
import com.kyra.iptv.player.StreamFailure
import com.kyra.iptv.player.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTestClassifierTest {

    private fun outcome(s: ProbeSignal) = StreamTestClassifier.classify(s).outcome

    // ---- aprovação ---------------------------------------------------------

    @Test fun readyWithMediaIsWorking() {
        val c = StreamTestClassifier.classify(ProbeSignal.Ready(bufferedMs = 1_200, httpStatus = 200))
        assertEquals(TestOutcome.WORKING, c.outcome)
        assertEquals(200, c.httpStatus)
    }

    @Test fun readyWithoutKnownStatusKeepsStatusNull() {
        assertNull(StreamTestClassifier.classify(ProbeSignal.Ready(bufferedMs = 800)).httpStatus)
    }

    @Test fun partialContentStatusIsKept() {
        assertEquals(206, StreamTestClassifier.classify(ProbeSignal.Ready(900, 206)).httpStatus)
    }

    @Test fun confirmationNeedsReadyAndEnoughBuffer() {
        val cfg = StreamTestConfig(confirmBufferedMs = 500)
        assertTrue(StreamTestClassifier.isConfirmed(true, 500, cfg))
        assertTrue(StreamTestClassifier.isConfirmed(true, 5_000, cfg))
        assertFalse(StreamTestClassifier.isConfirmed(true, 499, cfg))
        assertFalse(StreamTestClassifier.isConfirmed(true, 0, cfg))
        assertFalse(StreamTestClassifier.isConfirmed(false, 10_000, cfg)) // buffer sem READY não basta
    }

    @Test fun zeroBufferThresholdStillNeedsReady() {
        val cfg = StreamTestConfig(confirmBufferedMs = 0)
        assertTrue(StreamTestClassifier.isConfirmed(true, 0, cfg))
        assertFalse(StreamTestClassifier.isConfirmed(false, 0, cfg))
    }

    // ---- HTTP --------------------------------------------------------------

    @Test fun namedHttpStatuses() {
        mapOf(
            401 to TestOutcome.HTTP_401,
            403 to TestOutcome.HTTP_403,
            404 to TestOutcome.HTTP_404,
            408 to TestOutcome.HTTP_408,
            411 to TestOutcome.HTTP_411,
            429 to TestOutcome.HTTP_429,
        ).forEach { (code, expected) ->
            val c = StreamTestClassifier.classify(ProbeSignal.HttpError(code))
            assertEquals("HTTP $code", expected, c.outcome)
            assertEquals(code, c.httpStatus)
        }
    }

    @Test fun serverErrorsAreGroupedAs5xx() {
        listOf(500, 502, 503, 504, 599).forEach {
            assertEquals("HTTP $it", TestOutcome.HTTP_5XX, outcome(ProbeSignal.HttpError(it)))
        }
    }

    @Test fun otherHttpStatusesAreHttpOther() {
        listOf(400, 405, 410, 416, 451, 499, 600, 301, 200).forEach {
            assertEquals("HTTP $it", TestOutcome.HTTP_OTHER, outcome(ProbeSignal.HttpError(it)))
        }
    }

    @Test fun http403IsReportedSimplyAsForbidden() {
        val c = StreamTestClassifier.classify(ProbeSignal.HttpError(403))
        assertEquals("403 Forbidden", c.reason)
        assertFalse(c.outcome.isWorking)
    }

    @Test fun http5xxReasonShowsTheCode() {
        assertEquals("Erro do servidor (HTTP 503)", StreamTestClassifier.classify(ProbeSignal.HttpError(503)).reason)
        assertEquals("Erro HTTP 410", StreamTestClassifier.classify(ProbeSignal.HttpError(410)).reason)
    }

    // ---- demais sinais -----------------------------------------------------

    @Test fun nonHttpSignals() {
        assertEquals(TestOutcome.CONNECT_TIMEOUT, outcome(ProbeSignal.ConnectTimeout))
        assertEquals(TestOutcome.PREPARE_TIMEOUT, outcome(ProbeSignal.PrepareTimeout))
        assertEquals(TestOutcome.CONFIRM_TIMEOUT, outcome(ProbeSignal.ConfirmTimeout))
        assertEquals(TestOutcome.DNS, outcome(ProbeSignal.UnknownHost))
        assertEquals(TestOutcome.NETWORK, outcome(ProbeSignal.NetworkFailure))
        assertEquals(TestOutcome.FORMAT, outcome(ProbeSignal.FormatError))
        assertEquals(TestOutcome.DECODER, outcome(ProbeSignal.DecoderError))
        assertEquals(TestOutcome.CLEARTEXT, outcome(ProbeSignal.CleartextBlocked))
        assertEquals(TestOutcome.INCOMPATIBLE, outcome(ProbeSignal.EndedWithoutMedia))
        assertEquals(TestOutcome.INCOMPATIBLE, outcome(ProbeSignal.InvalidContentType))
        assertEquals(TestOutcome.OTHER, outcome(ProbeSignal.Other))
    }

    @Test fun nonHttpSignalsHaveNoStatus() {
        listOf(
            ProbeSignal.ConnectTimeout, ProbeSignal.PrepareTimeout, ProbeSignal.ConfirmTimeout,
            ProbeSignal.UnknownHost, ProbeSignal.NetworkFailure, ProbeSignal.FormatError,
            ProbeSignal.DecoderError, ProbeSignal.CleartextBlocked, ProbeSignal.EndedWithoutMedia,
            ProbeSignal.InvalidContentType, ProbeSignal.Other,
        ).forEach { assertNull(it.toString(), StreamTestClassifier.classify(it).httpStatus) }
    }

    @Test fun onlyReadyIsWorking() {
        val signals = listOf(
            ProbeSignal.HttpError(200), ProbeSignal.ConnectTimeout, ProbeSignal.PrepareTimeout,
            ProbeSignal.ConfirmTimeout, ProbeSignal.UnknownHost, ProbeSignal.NetworkFailure,
            ProbeSignal.FormatError, ProbeSignal.DecoderError, ProbeSignal.CleartextBlocked,
            ProbeSignal.EndedWithoutMedia, ProbeSignal.InvalidContentType, ProbeSignal.Other,
        )
        signals.forEach { assertFalse(it.toString(), outcome(it).isWorking) }
        assertTrue(outcome(ProbeSignal.Ready(1_000)).isWorking)
    }

    @Test fun reasonsNeverContainUrlsAndAreNotBlank() {
        val signals = listOf(
            ProbeSignal.Ready(1_000), ProbeSignal.HttpError(403), ProbeSignal.HttpError(503),
            ProbeSignal.HttpError(418), ProbeSignal.ConnectTimeout, ProbeSignal.PrepareTimeout,
            ProbeSignal.ConfirmTimeout, ProbeSignal.UnknownHost, ProbeSignal.NetworkFailure,
            ProbeSignal.FormatError, ProbeSignal.DecoderError, ProbeSignal.CleartextBlocked,
            ProbeSignal.EndedWithoutMedia, ProbeSignal.InvalidContentType, ProbeSignal.Other,
        )
        for (s in signals) {
            val r = StreamTestClassifier.classify(s).reason
            assertTrue(s.toString(), r.isNotBlank())
            assertFalse(s.toString(), r.contains("://"))
            assertFalse(s.toString(), r.contains("http", ignoreCase = true) && r.contains("/"))
        }
    }

    // ---- tradução do modelo que o player já usa ----------------------------

    @Test fun failureWithHttpStatusBecomesHttpError() {
        assertEquals(
            ProbeSignal.HttpError(404),
            ProbeSignal.fromFailure(StreamFailure(FailureKind.NETWORK, httpStatus = 404)),
        )
    }

    @Test fun failureKindsMapToSignals() {
        assertEquals(ProbeSignal.ConnectTimeout, ProbeSignal.fromFailure(StreamFailure(FailureKind.TIMEOUT)))
        assertEquals(ProbeSignal.NetworkFailure, ProbeSignal.fromFailure(StreamFailure(FailureKind.NETWORK)))
        assertEquals(ProbeSignal.CleartextBlocked, ProbeSignal.fromFailure(StreamFailure(FailureKind.CLEARTEXT_NOT_PERMITTED)))
        assertEquals(ProbeSignal.FormatError, ProbeSignal.fromFailure(StreamFailure(FailureKind.UNRECOGNIZED_FORMAT)))
        assertEquals(ProbeSignal.DecoderError, ProbeSignal.fromFailure(StreamFailure(FailureKind.DECODER)))
        assertEquals(ProbeSignal.Other, ProbeSignal.fromFailure(StreamFailure(FailureKind.OTHER)))
        assertEquals(ProbeSignal.Other, ProbeSignal.fromFailure(StreamFailure(FailureKind.BEHIND_LIVE_WINDOW)))
    }

    @Test fun unknownHostOverridesGenericNetworkFailure() {
        assertEquals(
            ProbeSignal.UnknownHost,
            ProbeSignal.fromFailure(StreamFailure(FailureKind.NETWORK), unknownHost = true),
        )
    }

    @Test fun httpStatusStillWinsOverUnknownHostFlag() {
        assertEquals(
            ProbeSignal.HttpError(403),
            ProbeSignal.fromFailure(StreamFailure(FailureKind.NETWORK, 403), unknownHost = true),
        )
    }

    // ---- resultado completo ------------------------------------------------

    @Test fun toResultKeepsChannelAndFillsFields() {
        val ch = Channel(
            id = "id1", name = "Canal", streamUrl = "http://example.com/live/user/pass/1.ts",
            group = "Filmes", tvgId = "t", tvgName = "Canal HD", logoUrl = "http://example.com/l.png",
            headers = mapOf("User-Agent" to "ua"),
        )
        val r = StreamTestClassifier.toResult(ch, ProbeSignal.HttpError(403), testedAt = 1234L, elapsedMs = 56L)
        assertEquals(ch, r.channel)
        assertEquals("id1", r.channel.id)
        assertEquals(TestOutcome.HTTP_403, r.outcome)
        assertEquals(403, r.httpStatus)
        assertEquals(1234L, r.testedAt)
        assertEquals(56L, r.elapsedMs)
        assertFalse(r.reason.contains("example.com"))
        assertFalse(r.reason.contains("pass"))
    }

    @Test fun toResultForWorkingChannel() {
        val ch = Channel(id = "id2", name = "C", streamUrl = "https://example.com/a.m3u8")
        val r = StreamTestClassifier.toResult(ch, ProbeSignal.Ready(2_000, 200), 1L, 900L)
        assertTrue(r.isWorking)
        assertEquals(200, r.httpStatus)
    }

    // ---- decisão após falha (sem retry) ------------------------------------

    private fun action(
        kind: FailureKind,
        status: Int? = null,
        type: StreamType = StreamType.HLS,
        hlsUsed: Boolean = false,
        live: Int = 0,
    ) = StreamTestClassifier.nextAction(StreamFailure(kind, status), type, hlsUsed, live)

    @Test fun neverRetries() {
        listOf(
            action(FailureKind.NETWORK),
            action(FailureKind.TIMEOUT),
            action(FailureKind.OTHER, 503),
            action(FailureKind.OTHER, 429),
            action(FailureKind.OTHER, 408),
        ).forEach { assertTrue(it.toString(), it is FailureAction.GiveUp) }
    }

    @Test fun triesHlsOnceForUnrecognizedFormatWithoutExtension() {
        assertTrue(action(FailureKind.UNRECOGNIZED_FORMAT, type = StreamType.UNKNOWN) is FailureAction.TryHls)
        assertTrue(
            action(FailureKind.UNRECOGNIZED_FORMAT, type = StreamType.UNKNOWN, hlsUsed = true) is FailureAction.GiveUp,
        )
    }

    @Test fun doesNotTryHlsWhenTypeIsKnown() {
        assertTrue(action(FailureKind.UNRECOGNIZED_FORMAT, type = StreamType.HLS) is FailureAction.GiveUp)
        assertTrue(action(FailureKind.UNRECOGNIZED_FORMAT, type = StreamType.DASH) is FailureAction.GiveUp)
    }

    @Test fun restartsLiveUpToTheLimitThenGivesUp() {
        for (done in 0 until PlaybackErrorPolicy.MAX_LIVE_RESTARTS) {
            assertTrue(action(FailureKind.BEHIND_LIVE_WINDOW, live = done) is FailureAction.RestartLive)
        }
        assertTrue(
            action(FailureKind.BEHIND_LIVE_WINDOW, live = PlaybackErrorPolicy.MAX_LIVE_RESTARTS) is FailureAction.GiveUp,
        )
    }
}
