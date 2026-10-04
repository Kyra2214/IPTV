package com.kyra.iptv.player

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext

/**
 * Cast do stream (não espelhamento) via Media3 [CastPlayer].
 *
 * Mantém o `CastContext` (pode não existir: aparelho sem Google Play Services) e um [CastPlayer]
 * que, durante uma transmissão, fica vivo mesmo com o app minimizado (liberá-lo encerraria a sessão). A troca ExoPlayer ↔ CastPlayer é feita pela
 * `PlayerActivity` a partir de [SessionAvailabilityListener].
 */
@UnstableApi
class CastManager(context: Context) {

    private val appContext = context.applicationContext

    /** `null` quando o Cast não pode ser usado neste aparelho; o botão simplesmente não aparece. */
    val castContext: CastContext? = try {
        CastContext.getSharedInstance(appContext)
    } catch (e: Exception) {
        null
    }

    val isAvailable: Boolean get() = castContext != null

    var player: CastPlayer? = null
        private set

    /** Há uma sessão Cast conectada e o [player] está pronto para ser usado. */
    val hasSession: Boolean get() = player?.isCastSessionAvailable == true

    /** Nome amigável do dispositivo conectado (ex.: "Sala"), se houver sessão. */
    val deviceName: String?
        get() = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    fun start(listener: SessionAvailabilityListener) {
        val ctx = castContext ?: return
        if (player != null) return
        player = CastPlayer(ctx).also { it.setSessionAvailabilityListener(listener) }
    }

    /**
     * Libera o [CastPlayer]. ATENÇÃO: no Media3, `CastPlayer.release()` encerra a sessão Cast
     * (`endCurrentSession`). Por isso só deve ser chamado ao sair de vez do player — não ao minimizar.
     */
    fun stop() {
        player?.setSessionAvailabilityListener(null)
        player?.release()
        player = null
    }

    /** Encerra a sessão Cast (o stream para no Chromecast). */
    fun endSession() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
