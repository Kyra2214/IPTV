package com.kyra.iptv.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Trabalho bloqueante (rede, disco, parsing) sempre fora da thread principal.
 * [run] devolve o resultado na thread principal; [execute] é para gravações rápidas, em ordem.
 */
object Background {
    private val heavy = Executors.newFixedThreadPool(2)
    private val quick = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        heavy.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }

    fun execute(work: () -> Unit) {
        quick.execute { runCatching(work) }
    }
}
