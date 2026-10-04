package com.kyra.iptv.player

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem

/**
 * Converte o item para o Cast marcando streams ao vivo como LIVE (o conversor padrão sempre usa
 * BUFFERED). Para o receptor, LIVE evita sondar duração/seek e inicia a reprodução mais rápido.
 * Só `.mp4` é tratado como arquivo (BUFFERED).
 */
@UnstableApi
class LiveMediaItemConverter : MediaItemConverter {
    private val default = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val base = default.toMediaQueueItem(mediaItem)
        val cfg = mediaItem.localConfiguration ?: return base
        val url = cfg.uri.toString()
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC)
        mediaItem.mediaMetadata.title?.let { metadata.putString(MediaMetadata.KEY_TITLE, it.toString()) }
        val info = MediaInfo.Builder(url)
            .setStreamType(
                if (cfg.mimeType == CastSupport.MIME_MP4) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE
            )
            .setContentType(cfg.mimeType ?: CastSupport.MIME_HLS)
            .setMetadata(metadata)
            .setCustomData(base.media?.customData) // necessário para toMediaItem() (ida e volta)
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(0.0).build()
    }

    override fun toMediaItem(item: MediaQueueItem): MediaItem = default.toMediaItem(item)
}
