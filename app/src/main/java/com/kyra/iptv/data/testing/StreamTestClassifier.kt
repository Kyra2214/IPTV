package com.kyra.iptv.data.testing

import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.player.FailureAction
import com.kyra.iptv.player.FailureKind
import com.kyra.iptv.player.PlaybackErrorPolicy
import com.kyra.iptv.player.RetryPolicy
import com.kyra.iptv.player.StreamFailure
import com.kyra.iptv.player.StreamType

/**
 * O que um teste de stream observou, já traduzido do Media3 (a tradução fica no `ExoStreamProbe`,
 * a única parte que depende de Android). Kotlin puro: [StreamTestClassifier] decide em cima disto.
 */
sealed class ProbeSignal {
    /** O player ficou pronto e há mídia em buffer ([bufferedMs]). [httpStatus] é melhor esforço. */
    data class Ready(val bufferedMs: Long, val httpStatus: Int? = null) : ProbeSignal()

    data class HttpError(val status: Int) : ProbeSignal()
    object ConnectTimeout : ProbeSignal()
    /** Não chegou a `STATE_READY` dentro de `prepareTimeoutMs`. */
    object PrepareTimeout : ProbeSignal()
    /** Ficou pronto, mas não houve mídia em buffer dentro de `confirmTimeoutMs`. */
    object ConfirmTimeout : ProbeSignal()
    object UnknownHost : ProbeSignal()
    object NetworkFailure : ProbeSignal()
    /** Contêiner/manifesto não reconhecido ou malformado. */
    object FormatError : ProbeSignal()
    object DecoderError : ProbeSignal()
    object CleartextBlocked : ProbeSignal()
    /** O player terminou (`STATE_ENDED`) sem nunca ficar pronto: resposta vazia/curta demais. */
    object EndedWithoutMedia : ProbeSignal()
    object InvalidContentType : ProbeSignal()
    object Other : ProbeSignal()

    companion object {
        /**
         * Reaproveita o modelo que o player já usa ([StreamFailure], produzido por `toFailure`).
         * [unknownHost] vem da cadeia de causas do erro (`UnknownHostException`), que o
         * [FailureKind] não distingue.
         */
        fun fromFailure(failure: StreamFailure, unknownHost: Boolean = false): ProbeSignal {
            failure.httpStatus?.let { return HttpError(it) }
            if (unknownHost) return UnknownHost
            return when (failure.kind) {
                FailureKind.TIMEOUT -> ConnectTimeout
                FailureKind.NETWORK -> NetworkFailure
                FailureKind.CLEARTEXT_NOT_PERMITTED -> CleartextBlocked
                FailureKind.UNRECOGNIZED_FORMAT -> FormatError
                FailureKind.DECODER -> DecoderError
                FailureKind.BEHIND_LIVE_WINDOW, FailureKind.OTHER -> Other
            }
        }
    }
}

/** Classificação de um sinal: resultado, código HTTP (se houver) e motivo curto, sem URL. */
data class Classification(val outcome: TestOutcome, val httpStatus: Int?, val reason: String)

/**
 * Regras puras do teste de stream: o que conta como funcional, como cada falha é classificada
 * e quando vale tentar de novo. Nenhum texto devolvido contém a URL (pode ter usuário/senha/token).
 */
object StreamTestClassifier {

    /** O teste não repete tentativas (é rápido de propósito); só o fallback HLS e o "voltar ao vivo" do player valem. */
    private val NO_RETRY = RetryPolicy(emptyList())

    /**
     * Critério de aprovação: o player ficou pronto **e** chegou mídia de verdade ao buffer.
     * HTTP 200 sozinho nunca basta.
     */
    fun isConfirmed(stateReady: Boolean, bufferedMs: Long, config: StreamTestConfig): Boolean =
        stateReady && bufferedMs >= config.confirmBufferedMs

    fun classify(signal: ProbeSignal): Classification = when (signal) {
        is ProbeSignal.Ready -> Classification(TestOutcome.WORKING, signal.httpStatus, "Reprodução confirmada")
        is ProbeSignal.HttpError -> classifyHttp(signal.status)
        ProbeSignal.ConnectTimeout ->
            of(TestOutcome.CONNECT_TIMEOUT, "Tempo esgotado ao conectar ao servidor")
        ProbeSignal.PrepareTimeout ->
            of(TestOutcome.PREPARE_TIMEOUT, "O stream não ficou pronto a tempo")
        ProbeSignal.ConfirmTimeout ->
            of(TestOutcome.CONFIRM_TIMEOUT, "Nenhuma mídia chegou a tempo")
        ProbeSignal.UnknownHost ->
            of(TestOutcome.DNS, "Servidor não encontrado (DNS)")
        ProbeSignal.NetworkFailure ->
            of(TestOutcome.NETWORK, "Sem conexão com o servidor do stream")
        ProbeSignal.FormatError ->
            of(TestOutcome.FORMAT, "Formato de stream não reconhecido")
        ProbeSignal.DecoderError ->
            of(TestOutcome.DECODER, "Este aparelho não consegue decodificar o stream")
        ProbeSignal.CleartextBlocked ->
            of(TestOutcome.CLEARTEXT, "Conexão sem criptografia não permitida")
        ProbeSignal.EndedWithoutMedia ->
            of(TestOutcome.INCOMPATIBLE, "A resposta terminou sem mídia reproduzível")
        ProbeSignal.InvalidContentType ->
            of(TestOutcome.INCOMPATIBLE, "Tipo de conteúdo incompatível com reprodução")
        ProbeSignal.Other ->
            of(TestOutcome.OTHER, "Não foi possível reproduzir este canal")
    }

    /** Resultado de HTTP por código: 401/403/404/408/411/429 têm categoria própria; 5xx agrupa; o resto é "outro". */
    fun outcomeForStatus(status: Int): TestOutcome = when (status) {
        401 -> TestOutcome.HTTP_401
        403 -> TestOutcome.HTTP_403
        404 -> TestOutcome.HTTP_404
        408 -> TestOutcome.HTTP_408
        411 -> TestOutcome.HTTP_411
        429 -> TestOutcome.HTTP_429
        in 500..599 -> TestOutcome.HTTP_5XX
        else -> TestOutcome.HTTP_OTHER
    }

    fun toResult(channel: Channel, signal: ProbeSignal, testedAt: Long, elapsedMs: Long): StreamTestResult {
        val c = classify(signal)
        return StreamTestResult(
            channel = channel,
            outcome = c.outcome,
            httpStatus = c.httpStatus,
            reason = c.reason,
            testedAt = testedAt,
            elapsedMs = elapsedMs,
        )
    }

    /**
     * O que fazer depois de uma falha do player durante o teste. É a mesma decisão da tela do
     * player ([PlaybackErrorPolicy]), sem retries: só `TryHls` (URL sem extensão), `RestartLive`
     * (até [PlaybackErrorPolicy.MAX_LIVE_RESTARTS]) ou `GiveUp`.
     */
    fun nextAction(
        failure: StreamFailure,
        streamType: StreamType,
        hlsFallbackUsed: Boolean,
        liveRestartsDone: Int,
    ): FailureAction = PlaybackErrorPolicy.decide(
        failure = failure,
        streamType = streamType,
        retriesDone = 0,
        liveRestartsDone = liveRestartsDone,
        hlsFallbackUsed = hlsFallbackUsed,
        retryPolicy = NO_RETRY,
    )

    private fun classifyHttp(status: Int): Classification {
        val outcome = outcomeForStatus(status)
        val reason = when (outcome) {
            TestOutcome.HTTP_5XX -> "Erro do servidor (HTTP $status)"
            TestOutcome.HTTP_OTHER -> "Erro HTTP $status"
            else -> outcome.label // "403 Forbidden", "404 Not Found"...
        }
        return Classification(outcome, status, reason)
    }

    private fun of(outcome: TestOutcome, reason: String) = Classification(outcome, null, reason)
}
