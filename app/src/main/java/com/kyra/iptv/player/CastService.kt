package com.kyra.iptv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.kyra.iptv.ui.player.PlayerActivity

/**
 * Serviço em primeiro plano com notificação fixa enquanto há uma sessão Cast. Mantém o processo vivo
 * com o app minimizado e dá um atalho para voltar ao player e para parar a transmissão.
 * Encerra sozinho quando a sessão Cast termina.
 */
class CastService : Service() {

    private var sessionManager: com.google.android.gms.cast.framework.SessionManager? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(session: CastSession, error: Int) = shutdown()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { CastContext.getSharedInstance(this).sessionManager.endCurrentSession(true) }
            shutdown()
            return START_NOT_STICKY
        }
        val device = intent?.getStringExtra(EXTRA_DEVICE)
        val channel = intent?.getStringExtra(EXTRA_CHANNEL)
        val notification = buildNotification(device, channel)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (sessionManager == null) {
            sessionManager = runCatching { CastContext.getSharedInstance(this).sessionManager }.getOrNull()
                ?.also { it.addSessionManagerListener(sessionListener, CastSession::class.java) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        sessionManager = null
        super.onDestroy()
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(device: String?, channel: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmissão (Cast)", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (device != null) "Transmitindo para $device" else "Transmitindo")
            .setContentText(channel ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar transmissão", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.kyra.iptv.CAST_STOP"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_CHANNEL = "channel"

        /** Inicia (ou atualiza) a notificação fixa. Chamar com o app em primeiro plano. */
        fun start(context: Context, device: String?, channelName: String?) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CastService::class.java)
                        .putExtra(EXTRA_DEVICE, device)
                        .putExtra(EXTRA_CHANNEL, channelName),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastService::class.java)) }
        }
    }
}
