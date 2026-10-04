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

    private var attached: SessionAvailabilityListener? = null

    /**
     * Liga [listener] ao [CastPlayer]. O CastPlayer é criado uma vez e vive com o processo (ver
     * [IptvApp.cast]): assim a transmissão continua ao voltar para a lista de canais e escolher
     * outro canal. `CastPlayer.release()` encerra a sessão Cast, por isso nunca é chamado.
     */
    fun start(listener: SessionAvailabilityListener) {
        val ctx = castContext ?: return
        val p = player ?: CastPlayer(ctx, LiveMediaItemConverter()).also { player = it }
        attached = listener
        p.setSessionAvailabilityListener(listener)
    }

    /** Desliga [listener] (só se ainda for o ativo); a sessão e o [CastPlayer] continuam. */
    fun detach(listener: SessionAvailabilityListener) {
        if (attached === listener) {
            attached = null
            player?.setSessionAvailabilityListener(null)
        }
    }

    /** Encerra a sessão Cast (o stream para no Chromecast). */
    fun endSession() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
