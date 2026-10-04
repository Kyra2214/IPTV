package com.kyra.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {

    private fun decide(
        failure: StreamFailure,
        type: StreamType = StreamType.HLS,
        retries: Int = 0,
        live: Int = 0,
        hlsUsed: Boolean = false,
    ) = PlaybackErrorPolicy.decide(failure, type, retries, live, hlsUsed)

    private val network = StreamFailure(FailureKind.NETWORK)

    @Test fun networkErrorsRetryWithGrowingDelayThenGiveUp() {
        assertEquals(FailureAction.Retry(1_000, 1, 3), decide(network, retries = 0))
        assertEquals(FailureAction.Retry(2_000, 2, 3), decide(network, retries = 1))
        assertEquals(FailureAction.Retry(4_000, 3, 3), decide(network, retries = 2))
        assertEquals(FailureAction.GiveUp("Sem conexão com o servidor do stream."), decide(network, retries = 3))
    }

    @Test fun timeoutIsRetriedAndHasItsOwnMessage() {
        val timeout = StreamFailure(FailureKind.TIMEOUT)
        assertTrue(decide(timeout) is FailureAction.Retry)
        assertEquals(FailureAction.GiveUp("Tempo esgotado ao conectar ao stream."), decide(timeout, retries = 3))
    }

    @Test fun permanentHttpStatusesGiveUpImmediately() {
        assertEquals(FailureAction.GiveUp("Acesso negado pelo servidor (HTTP 401)."), decide(StreamFailure(FailureKind.NETWORK, 401)))
        assertEquals(FailureAction.GiveUp("Acesso negado pelo servidor (HTTP 403)."), decide(StreamFailure(FailureKind.NETWORK, 403)))
        assertEquals(FailureAction.GiveUp("Stream não encontrado (HTTP 404)."), decide(StreamFailure(FailureKind.NETWORK, 404)))
        assertEquals(FailureAction.GiveUp("Stream não encontrado (HTTP 410)."), decide(StreamFailure(FailureKind.NETWORK, 410)))
        assertEquals(FailureAction.GiveUp("O servidor respondeu com erro (HTTP 400)."), decide(StreamFailure(FailureKind.NETWORK, 400)))
    }

    @Test fun transientHttpStatusesRetryThenGiveUp() {
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            assertTrue("HTTP $code deveria repetir", decide(StreamFailure(FailureKind.NETWORK, code)) is FailureAction.Retry)
        }
        assertEquals(
            FailureAction.GiveUp("O servidor respondeu com erro (HTTP 503)."),
            decide(StreamFailure(FailureKind.NETWORK, 503), retries = 3),
        )
    }

    @Test fun behindLiveWindowRestartsAtMostThreeTimesThenFollowsTheRetryFlow() {
        val behind = StreamFailure(FailureKind.BEHIND_LIVE_WINDOW)
        for (n in 0 until PlaybackErrorPolicy.MAX_LIVE_RESTARTS) assertEquals(FailureAction.RestartLive, decide(behind, live = n))
        assertEquals(FailureAction.Retry(1_000, 1, 3), decide(behind, live = PlaybackErrorPolicy.MAX_LIVE_RESTARTS))
        assertEquals(
            FailureAction.GiveUp("Não foi possível reproduzir este canal."),
            decide(behind, live = PlaybackErrorPolicy.MAX_LIVE_RESTARTS, retries = 3),
        )
    }

    @Test fun unrecognisedFormatTriesHlsOnlyOnceAndOnlyForUnknownType() {
        val unrecognised = StreamFailure(FailureKind.UNRECOGNIZED_FORMAT)
        assertEquals(FailureAction.TryHls, decide(unrecognised, type = StreamType.UNKNOWN))
        val gaveUp = FailureAction.GiveUp("Formato de stream não suportado.")
        assertEquals(gaveUp, decide(unrecognised, type = StreamType.UNKNOWN, hlsUsed = true))
        assertEquals(gaveUp, decide(unrecognised, type = StreamType.HLS))
        assertEquals(gaveUp, decide(unrecognised, type = StreamType.DASH))
    }

    @Test fun nonRetriableKindsGiveUpWithTheirOwnMessages() {
        assertEquals(FailureAction.GiveUp("Conexão sem criptografia não permitida."), decide(StreamFailure(FailureKind.CLEARTEXT_NOT_PERMITTED)))
        assertEquals(FailureAction.GiveUp("Este aparelho não consegue decodificar o stream."), decide(StreamFailure(FailureKind.DECODER)))
        assertEquals(FailureAction.GiveUp("Não foi possível reproduzir este canal."), decide(StreamFailure(FailureKind.OTHER)))
    }

    @Test fun messagesNeverContainAUrl() {
        for (kind in FailureKind.values()) {
            for (status in listOf(null, 401, 404, 500)) {
                val m = PlaybackErrorPolicy.message(StreamFailure(kind, status))
                assertFalse(m, m.contains("://"))
                assertTrue(m.isNotBlank())
            }
        }
    }

    @Test fun customRetryPolicyIsRespected() {
        val one = RetryPolicy(listOf(10L))
        assertEquals(FailureAction.Retry(10, 1, 1), PlaybackErrorPolicy.decide(network, StreamType.HLS, 0, 0, false, one))
        assertTrue(PlaybackErrorPolicy.decide(network, StreamType.HLS, 1, 0, false, one) is FailureAction.GiveUp)
    }
}
