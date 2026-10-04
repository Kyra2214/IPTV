package com.kyra.iptv.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.kyra.iptv.ui.player.PlayerActivity

/**
 * Opções do Cast com notificação de mídia (play/pause e parar transmissão). A notificação roda num
 * serviço em primeiro plano do SDK, que mantém o app vivo e a sessão ativa com a tela minimizada.
 * Tocar nela reabre o [PlayerActivity].
 */
class IptvCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_STOP_CASTING),
                intArrayOf(0, 1),
            )
            .setTargetActivityClassName(PlayerActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
