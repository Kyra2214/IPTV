package com.kyra.iptv.player

/** Tipo de falha de reprodução, já traduzido dos códigos do Media3 (a tradução fica na tela do player). */
enum class FailureKind {
    /** Janela ao vivo ultrapassada: basta voltar ao ponto ao vivo. */
    BEHIND_LIVE_WINDOW,
    NETWORK,
    TIMEOUT,
    CLEARTEXT_NOT_PERMITTED,
    /** Formato/contêiner não reconhecido ou malformado. */
    UNRECOGNIZED_FORMAT,
    DECODER,
    OTHER,
}

data class StreamFailure(val kind: FailureKind, val httpStatus: Int? = null)

/** O que a tela do player deve fazer depois de uma falha. */
sealed class FailureAction {
    /** Volta ao ponto ao vivo e prepara de novo (sem contar como retry). */
    object RestartLive : FailureAction()

    /** URL sem extensão que o ExoPlayer não reconheceu: tenta uma vez como HLS. */
    object TryHls : FailureAction()

    data class Retry(val delayMs: Long, val attempt: Int, val maxAttempts: Int) : FailureAction()

    /** Desiste e mostra [message] (nunca contém a URL) com o botão "Tentar novamente". */
    data class GiveUp(val message: String) : FailureAction()
}

/**
 * Decisão pura (sem Android/Media3) sobre uma falha de reprodução: voltar ao ao vivo, tentar HLS,
 * repetir com espera crescente ou desistir. Testável na JVM.
 */
object PlaybackErrorPolicy {

    /** Limite de "voltar ao ao vivo" seguidos; depois disso a falha segue o fluxo normal de retry. */
    const val MAX_LIVE_RESTARTS = 3

    fun decide(
        failure: StreamFailure,
        streamType: StreamType,
        retriesDone: Int,
        liveRestartsDone: Int,
        hlsFallbackUsed: Boolean,
        retryPolicy: RetryPolicy = RetryPolicy(),
    ): FailureAction {
        if (failure.kind == FailureKind.BEHIND_LIVE_WINDOW && liveRestartsDone < MAX_LIVE_RESTARTS) {
            return FailureAction.RestartLive
        }
        if (failure.kind == FailureKind.UNRECOGNIZED_FORMAT && !hlsFallbackUsed && streamType == StreamType.UNKNOWN) {
            return FailureAction.TryHls
        }
        val retriable = when {
            failure.httpStatus != null -> RetryPolicy.isRetriableStatus(failure.httpStatus)
            else -> failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT ||
                failure.kind == FailureKind.BEHIND_LIVE_WINDOW // só chega aqui depois de estourar MAX_LIVE_RESTARTS
        }
        val delay = if (retriable) retryPolicy.delayFor(retriesDone) else null
        return if (delay != null) {
            FailureAction.Retry(delay, attempt = retriesDone + 1, maxAttempts = retryPolicy.maxAttempts)
        } else {
            FailureAction.GiveUp(message(failure))
        }
    }

    /** Texto para o usuário; nunca inclui a URL do stream (pode ter usuário/senha/token). */
    fun message(failure: StreamFailure): String {
        val status = failure.httpStatus
        return when {
            status == 401 || status == 403 -> "Acesso negado pelo servidor (HTTP $status)."
            status == 404 || status == 410 -> "Stream não encontrado (HTTP $status)."
            status != null -> "O servidor respondeu com erro (HTTP $status)."
            failure.kind == FailureKind.TIMEOUT -> "Tempo esgotado ao conectar ao stream."
            failure.kind == FailureKind.NETWORK -> "Sem conexão com o servidor do stream."
            failure.kind == FailureKind.CLEARTEXT_NOT_PERMITTED -> "Conexão sem criptografia não permitida."
            failure.kind == FailureKind.UNRECOGNIZED_FORMAT -> "Formato de stream não suportado."
            failure.kind == FailureKind.DECODER -> "Este aparelho não consegue decodificar o stream."
            else -> "Não foi possível reproduzir este canal."
        }
    }
}
