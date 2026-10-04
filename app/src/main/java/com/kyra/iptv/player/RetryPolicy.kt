package com.kyra.iptv.player

/**
 * Retry controlado: poucas tentativas automáticas, com espera crescente.
 * Depois disso o usuário decide (botão "Tentar novamente").
 */
class RetryPolicy(private val delaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L)) {

    val maxAttempts: Int get() = delaysMs.size

    /** Espera antes da próxima tentativa, dado quantas já foram feitas; `null` = desistir. */
    fun delayFor(attemptsDone: Int): Long? = delaysMs.getOrNull(attemptsDone)

    companion object {
        /** Só vale tentar de novo quando o erro HTTP pode ser passageiro (4xx de acesso/ausência não é). */
        fun isRetriableStatus(code: Int): Boolean = code == 408 || code == 429 || code >= 500
    }
}
