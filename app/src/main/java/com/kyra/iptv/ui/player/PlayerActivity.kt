package com.kyra.iptv.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.kyra.iptv.IptvApp
import com.kyra.iptv.data.model.Channel
import com.kyra.iptv.player.CastManager
import com.kyra.iptv.player.CastSupport
import com.kyra.iptv.player.ChannelQueue
import com.kyra.iptv.player.FailureAction
import com.kyra.iptv.player.MediaSupport
import com.kyra.iptv.player.PlaybackErrorPolicy
import com.kyra.iptv.player.RetryPolicy
import com.kyra.iptv.player.StreamFailure
import com.kyra.iptv.player.StreamSupport
import com.kyra.iptv.player.StreamType
import com.kyra.iptv.ui.Background
import com.kyra.iptv.ui.applySystemBarsPadding
import com.kyra.iptv.ui.dp

/**
 * Tela 3 — Player (Media3/ExoPlayer): HTTP/HTTPS, HLS, DASH, headers por canal, redirects,
 * buffering, erro com retry controlado, canal anterior/próximo e tela cheia (paisagem).
 *
 * Chromecast (Fase 6): botão Cast na barra superior (só aparece com dispositivos na rede). Ao
 * conectar, o ExoPlayer é liberado e o canal atual vai para o [androidx.media3.cast.CastPlayer];
 * ao desconectar, volta para o ExoPlayer. Trocar de canal durante o Cast transmite o novo canal.
 * Sair da tela durante o Cast encerra a transmissão; Home mantém (ao voltar, o canal é recarregado).
 *
 * A fila vem de [IptvApp.playbackQueue] (a lista que o usuário via). Se o processo foi recriado e a
 * fila se perdeu, ela é remontada a partir da lista (EXTRA_PLAYLIST_ID) e do último canal.
 * O player existe só entre onStart e onStop; a rotação não recria a Activity (ver Manifest).
 */
@UnstableApi
class PlayerActivity : FragmentActivity() {

    private val app get() = application as IptvApp
    private val handler = Handler(Looper.getMainLooper())
    private val retryPolicy = RetryPolicy()

    private var queue: ChannelQueue? = null
    private var player: ExoPlayer? = null
    private var started = false
    private var retries = 0
    private var liveRestarts = 0
    private var hlsFallback = false
    private var errorShown = false
    private var casting = false
    private lateinit var cast: CastManager

    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var statusBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var castBox: LinearLayout
    private lateinit var castTitle: TextView
    private lateinit var castNote: TextView

    private val retryRunnable = Runnable { queue?.current?.let { load(it) } }
    private val hideControlsRunnable = Runnable { if (!errorShown) setControlsVisible(false) }

    private val sessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() = enterCast()
        override fun onCastSessionUnavailable() = exitCast()
    }

    /** Player em uso agora: o CastPlayer durante a transmissão, senão o ExoPlayer. */
    private val activePlayer: Player? get() = if (casting) cast.player else player

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retries = 0
                liveRestarts = 0
                hideStatus()
                scheduleHideControls()
            }
            updateBuffering()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseButton.text = if (isPlaying) "⏸" else "▶"
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    // ---- ciclo de vida --------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cast = CastManager(this)
        setContentView(buildUi())
        applyFullscreen()

        queue = app.playbackQueue
        if (queue == null) {
            val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
            if (playlistId == null) {
                finish()
                return
            }
            // Após recriação do processo o Android devolve o Intent original; o canal atual vem do estado salvo.
            restoreQueue(playlistId, savedInstanceState?.getString(EXTRA_CHANNEL_ID) ?: intent.getStringExtra(EXTRA_CHANNEL_ID))
        } else {
            showChannelInfo()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        cast.start(sessionListener)
        cast.player?.addListener(listener)
        startPlayback()
    }

    override fun onStop() {
        started = false
        releasePlayer()
        cast.player?.removeListener(listener)
        cast.stop()
        // Saiu da tela (voltar/←) durante o Cast: encerra a transmissão. Home mantém a sessão.
        if (isFinishing && casting) cast.endSession()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        queue?.current?.let { outState.putString(EXTRA_CHANNEL_ID, it.id) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreen()
    }

    private fun restoreQueue(playlistId: String, channelId: String?) {
        Background.run({ app.playlists.loadChannels(playlistId).channels }) { result ->
            if (isFinishing || isDestroyed) return@run
            val channels = result.getOrNull()
            if (channels.isNullOrEmpty()) {
                showError("Não foi possível abrir a lista.", canRetry = false)
                return@run
            }
            val q = ChannelQueue.startingAt(channels, channelId)
            queue = q
            app.playbackQueue = q
            q.current?.let { ch -> Background.execute { app.history.record(ch.id) } }
            showChannelInfo()
            if (started) startPlayback()
        }
    }

    // ---- reprodução -----------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(this)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also {
            it.addListener(listener)
            playerView.player = it
            player = it
        }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
    }

    /** Troca de canal: cancela retries pendentes, zera tentativas e começa o novo stream. */
    private fun switchTo(ch: Channel?) {
        ch ?: return
        handler.removeCallbacks(retryRunnable)
        retries = 0
        liveRestarts = 0
        hlsFallback = false
        intent.putExtra(EXTRA_CHANNEL_ID, ch.id)
        Background.execute { app.history.record(ch.id) }
        showChannelInfo()
        load(ch)
    }

    /** Começa (ou retoma) a reprodução do canal atual no player certo: Cast, se já houver sessão; senão local. */
    private fun startPlayback() {
        val ch = queue?.current ?: return
        casting = cast.hasSession
        castBox.visibility = if (casting) View.VISIBLE else View.GONE
        load(ch)
    }

    private fun load(ch: Channel) {
        handler.removeCallbacks(retryRunnable)
        hideStatus()
        buffering.visibility = View.VISIBLE
        if (!StreamSupport.isPlayable(ch.streamUrl)) {
            showError("Endereço do stream inválido.", canRetry = false)
            return
        }
        if (casting) loadCast(ch) else loadLocal(ch)
    }

    // ---- Chromecast ------------------------------------------------------------

    /** Sessão Cast conectada: libera o ExoPlayer e transmite o canal atual. */
    private fun enterCast() {
        if (casting) return
        val ch = queue?.current ?: return
        casting = true
        handler.removeCallbacks(retryRunnable)
        retries = 0
        liveRestarts = 0
        hlsFallback = false
        releasePlayer()
        castBox.visibility = View.VISIBLE
        load(ch)
    }

    /** Sessão encerrada (usuário, receptor desligado ou rede): volta para o ExoPlayer no aparelho. */
    private fun exitCast() {
        if (!casting) return
        casting = false
        cast.player?.stop()
        cast.player?.clearMediaItems()
        castBox.visibility = View.GONE
        hideStatus()
        if (started) queue?.current?.let { load(it) }
    }

    private fun loadCast(ch: Channel) {
        val cp = cast.player ?: return
        val device = cast.deviceName
        castTitle.text = if (device != null) "Transmitindo para $device" else "Transmitindo"
        castNote.text = if (CastSupport.dependsOnHeaders(ch.headers)) CastSupport.HEADERS_WARNING else ""
        castNote.visibility = if (castNote.text.isEmpty()) View.GONE else View.VISIBLE

        val item = MediaItem.Builder()
            .setUri(ch.streamUrl)
            .setMimeType(CastSupport.mimeType(ch.streamUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .build()
        cp.setMediaItem(item)
        cp.playWhenReady = true
        cp.prepare()
    }

    /** Prepara [ch] no ExoPlayer (headers próprios, redirects entre http/https, timeouts). */
    private fun loadLocal(ch: Channel) {
        val http = MediaSupport.httpFactory(ch.headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        val type = if (hlsFallback) StreamType.HLS else StreamSupport.detectType(ch.streamUrl)
        val item = MediaSupport.mediaItem(ch.streamUrl, type)

        val p = ensurePlayer()
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private fun handleError(e: PlaybackException) {
        val ch = queue?.current ?: return
        if (casting) {
            // O receptor acessa a URL sozinho; sem proxy no MVP, não há como "consertar" daqui.
            showError(CastSupport.REMOTE_ERROR, canRetry = true)
            return
        }
        val failure = toFailure(e)
        when (val action = PlaybackErrorPolicy.decide(
            failure = failure,
            streamType = StreamSupport.detectType(ch.streamUrl),
            retriesDone = retries,
            liveRestartsDone = liveRestarts,
            hlsFallbackUsed = hlsFallback,
            retryPolicy = retryPolicy,
        )) {
            FailureAction.RestartLive -> {
                liveRestarts++
                player?.let { it.seekToDefaultPosition(); it.prepare() }
            }
            FailureAction.TryHls -> {
                hlsFallback = true
                load(ch)
            }
            is FailureAction.Retry -> {
                retries = action.attempt
                buffering.visibility = View.VISIBLE
                showStatus("Falha na reprodução. Nova tentativa ${action.attempt} de ${action.maxAttempts}…", canRetry = false)
                handler.postDelayed(retryRunnable, action.delayMs)
            }
            is FailureAction.GiveUp -> showError(action.message, canRetry = true)
        }
    }

    /** Traduz o erro do Media3 para o modelo puro de [PlaybackErrorPolicy]. */
    private fun toFailure(e: PlaybackException): StreamFailure = MediaSupport.toFailure(e)

    // ---- ações ----------------------------------------------------------------

    private fun togglePlayPause() {
        val p = activePlayer ?: return
        when {
            p.playbackState == Player.STATE_IDLE -> manualRetry()
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
        scheduleHideControls()
    }

    private fun manualRetry() {
        retries = 0
        liveRestarts = 0
        hlsFallback = false
        queue?.current?.let { load(it) }
    }

    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { switchTo(queue?.next()); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchTo(queue?.previous()); true }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            togglePlayPause(); true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    // ---- interface ------------------------------------------------------------

    private fun showChannelInfo() {
        val q = queue ?: return
        val ch = q.current ?: return
        titleView.text = ch.name
        subtitleView.text = "${q.index + 1} de ${q.size} · ${ch.group}"
        prevButton.isEnabled = q.hasMultiple
        nextButton.isEnabled = q.hasMultiple
        prevButton.alpha = if (q.hasMultiple) 1f else 0.35f
        nextButton.alpha = if (q.hasMultiple) 1f else 0.35f
    }

    private fun updateBuffering() {
        val p = activePlayer
        buffering.visibility =
            if (!errorShown && p != null && p.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
    }

    private fun showStatus(message: String, canRetry: Boolean) {
        statusText.text = message
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        statusBox.visibility = View.VISIBLE
    }

    private fun showError(message: String, canRetry: Boolean) {
        errorShown = true
        buffering.visibility = View.GONE
        showStatus(message, canRetry)
        setControlsVisible(true)
    }

    private fun hideStatus() {
        errorShown = false
        statusBox.visibility = View.GONE
    }

    private fun setControlsVisible(visible: Boolean) {
        handler.removeCallbacks(hideControlsRunnable)
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
        if (visible) scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!errorShown && topBar.visibility == View.VISIBLE) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    /** Paisagem = tela cheia imersiva (barras do sistema só com gesto); retrato mostra as barras. */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun control(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 28f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick(); scheduleHideControls() }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // o spinner é nosso
            setOnClickListener { setControlsVisible(topBar.visibility != View.VISIBLE) }
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        titleView = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFCCCCCC.toInt()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            addView(control("←") { finish() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subtitleView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (cast.isAvailable) {
                // O botão some sozinho quando não há dispositivos Cast na rede.
                addView(MediaRouteButton(context).also { CastButtonFactory.setUpMediaRouteButton(applicationContext, it) })
            }
            setPadding(0, 0, dp(16), 0)
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        prevButton = control("⏮") { switchTo(queue?.previous()) }
        playPauseButton = control("⏸") { togglePlayPause() }
        nextButton = control("⏭") { switchTo(queue?.next()) }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            addView(prevButton)
            addView(playPauseButton)
            addView(nextButton)
            addView(control("⛶") { toggleFullscreen() })
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        buffering = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(buffering, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        statusText = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        retryButton = Button(this).apply {
            text = "Tentar novamente"
            setOnClickListener { manualRetry() }
        }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
            addView(statusText)
            addView(retryButton)
        }

        castTitle = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        castNote = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFFFCC80.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        castBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
            addView(castTitle)
            addView(castNote)
            addView(Button(context).apply {
                text = "Parar transmissão"
                setOnClickListener { cast.endSession() }
            })
        }
        // Centro da tela: aviso de Cast (se houver) e, abaixo, loading/erro.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(castBox)
            addView(statusBox)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.applySystemBarsPadding()
        return root
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_CHANNEL_ID = "channel_id"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val CONTROLS_TIMEOUT_MS = 4_000L

        /** A fila deve estar em [IptvApp.playbackQueue]; os extras só servem para recriação do processo. */
        fun start(context: Context, playlistId: String, channelId: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
            )
        }
    }
}
