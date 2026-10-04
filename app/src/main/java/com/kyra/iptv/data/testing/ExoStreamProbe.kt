package com.kyra.iptv.data.testing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.player.FailureAction
import com.kyra.iptv.player.MediaSupport
import com.kyra.iptv.player.StreamSupport
import com.kyra.iptv.player.StreamType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [StreamProbe] real: testa UM stream com um ExoPlayer próprio, mudo e sem tela, e só aprova se o
 * player ficou pronto **e** chegou mídia ao buffer (critério em [StreamTestClassifier.isConfirmed]).
 *
 * - Todo o ExoPlayer vive na thread principal (exigência do Media3); [start] só agenda e devolve.
 * - Cada teste tem seu próprio player, liberado **antes** de [onDone] ser chamado (contrato de [StreamProbe]).
 * - [onDone] é chamado exatamente uma vez: aprovação, falha, timeout ou cancelamento.
 * - O teste lê o stream igual ao player da tela (mesmos headers, redirects e tipo, via [MediaSupport]),
 *   com buffer mínimo e a menor qualidade, para gastar pouca banda e memória com vários em paralelo.
 * - Nenhum texto produzido aqui contém a URL.
 */
@UnstableApi
class ExoStreamProbe(context: Context) : StreamProbe {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun start(channel: Channel, config: StreamTestConfig, onDone: (ProbeSignal) -> Unit): ProbeHandle {
        val run = Run(channel, config, onDone)
        main.post { run.begin() }
        return ProbeHandle { main.post { run.finish(ProbeSignal.Other) } }
    }

    /** Um teste. Só é tocado pela thread principal (`begin`, listeners, `finish` e os timers). */
    private inner class Run(
        private val channel: Channel,
        private val config: StreamTestConfig,
        private val onDone: (ProbeSignal) -> Unit,
    ) {
        private val done = AtomicBoolean(false)
        private var player: ExoPlayer? = null
        private var ready = false
        private var hlsFallback = false
        private var liveRestarts = 0
        private var maxBufferedMs = 0L

        private val prepareTimeout = Runnable { finish(ProbeSignal.PrepareTimeout) }
        private val confirmTimeout = Runnable {
            // Última chance: o buffer pode ter enchido entre duas leituras.
            if (isConfirmed()) finish(readySignal()) else finish(ProbeSignal.ConfirmTimeout)
        }
        private val poll = object : Runnable {
            override fun run() {
                if (done.get()) return
                if (isConfirmed()) finish(readySignal()) else main.postDelayed(this, POLL_MS)
            }
        }

        fun begin() {
            if (done.get()) return // cancelado antes de começar
            if (!StreamSupport.isPlayable(channel.streamUrl)) {
                finish(ProbeSignal.Other)
                return
            }
            try {
                player = buildPlayer().also { it.addListener(listener) }
                load()
                main.postDelayed(prepareTimeout, config.prepareTimeoutMs)
            } catch (t: Throwable) {
                finish(ProbeSignal.Other)
            }
        }

        private fun buildPlayer(): ExoPlayer {
            // O DefaultLoadControl exige "buffer para tocar" <= buffer mínimo; limita se a configuração pedir mais.
            val startMs = config.confirmBufferedMs.coerceIn(1L, MIN_BUFFER_MS.toLong()).toInt()
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, startMs, startMs)
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            val selector = DefaultTrackSelector(appContext).apply {
                parameters = buildUponParameters().setForceLowestBitrate(true).build()
            }
            return ExoPlayer.Builder(appContext)
                .setLooper(Looper.getMainLooper())
                .setLoadControl(loadControl)
                .setTrackSelector(selector)
                .build()
                .apply {
                    volume = 0f
                    playWhenReady = false // só precisa encher o buffer; nada toca
                }
        }

        private fun load() {
            val p = player ?: return
            val http = MediaSupport.httpFactory(channel.headers, config.connectTimeoutMs, config.readTimeoutMs)
            val type = if (hlsFallback) StreamType.HLS else StreamSupport.detectType(channel.streamUrl)
            val item = MediaSupport.mediaItem(channel.streamUrl, type)
            p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
            p.prepare()
        }

        private val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (done.get()) return
                when (state) {
                    Player.STATE_READY -> {
                        if (!ready) {
                            ready = true
                            main.removeCallbacks(prepareTimeout)
                            main.postDelayed(confirmTimeout, config.confirmTimeoutMs)
                        }
                        main.removeCallbacks(poll) // evita duas cadeias de leitura se voltar a READY
                        poll.run()
                    }
                    Player.STATE_ENDED -> {
                        // Terminou: aprova só se ficou pronto e houve mídia suficiente (VOD curto).
                        sampleBuffer()
                        val confirmed = ready &&
                            StreamTestClassifier.isConfirmed(true, maxBufferedMs, config)
                        finish(if (confirmed) readySignal() else ProbeSignal.EndedWithoutMedia)
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (done.get()) return
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE) {
                    finish(ProbeSignal.InvalidContentType)
                    return
                }
                val failure = MediaSupport.toFailure(error)
                val action = StreamTestClassifier.nextAction(
                    failure = failure,
                    streamType = StreamSupport.detectType(channel.streamUrl),
                    hlsFallbackUsed = hlsFallback,
                    liveRestartsDone = liveRestarts,
                )
                when (action) {
                    FailureAction.RestartLive -> {
                        liveRestarts++
                        player?.let { it.seekToDefaultPosition(); it.prepare() }
                    }
                    FailureAction.TryHls -> {
                        hlsFallback = true
                        ready = false
                        main.removeCallbacks(confirmTimeout)
                        try { load() } catch (t: Throwable) { finish(ProbeSignal.Other) }
                    }
                    // Sem retries no teste (RetryPolicy vazia): qualquer outra decisão encerra.
                    is FailureAction.Retry, is FailureAction.GiveUp ->
                        finish(ProbeSignal.fromFailure(failure, MediaSupport.isUnknownHost(error)))
                }
            }
        }

        private fun sampleBuffer() {
            val p = player ?: return
            val b = p.totalBufferedDuration
            if (b != C.TIME_UNSET && b > maxBufferedMs) maxBufferedMs = b
        }

        private fun isConfirmed(): Boolean {
            sampleBuffer()
            return StreamTestClassifier.isConfirmed(ready, maxBufferedMs, config)
        }

        private fun readySignal() = ProbeSignal.Ready(maxBufferedMs)

        /** Encerra uma única vez: para os timers, libera o player e só então avisa. */
        fun finish(signal: ProbeSignal) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(prepareTimeout)
            main.removeCallbacks(confirmTimeout)
            main.removeCallbacks(poll)
            val p = player
            player = null
            try {
                p?.removeListener(listener)
                p?.release()
            } catch (_: Throwable) {
            }
            try {
                onDone(signal)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val MIN_BUFFER_MS = 1_000
        const val MAX_BUFFER_MS = 3_000
        const val TARGET_BUFFER_BYTES = 2 * 1024 * 1024
    }
}
