package com.kyra.iptv

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.media3.common.util.UnstableApi
import com.kyra.iptv.data.repository.FavoritesRepository
import com.kyra.iptv.data.testing.ExoStreamProbe
import com.kyra.iptv.data.testing.StreamProbe
import com.kyra.iptv.data.testing.StreamTestSession
import com.kyra.iptv.data.repository.HistoryRepository
import com.kyra.iptv.data.repository.PlaylistRepository
import com.kyra.iptv.player.ChannelQueue
import com.kyra.iptv.storage.LocalStorage
import java.io.File

/** Ponto único de criação dos serviços (sem framework de DI, ver PLANO.md §10). */
class IptvApp : Application() {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Release: sem logs do Media3 (podem trazer host/URL de streams, que costumam ter usuário/senha/token).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        }
    }

    val storage: LocalStorage by lazy { LocalStorage(File(filesDir, "iptv")) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(storage) }
    val favorites: FavoritesRepository by lazy { FavoritesRepository(storage) }
    val history: HistoryRepository by lazy { HistoryRepository(storage) }

    /** Teste de lista em andamento (ou concluído) mostrado por `TestListActivity`; sobrevive à rotação da tela. */
    @Volatile var testSession: StreamTestSession? = null

    /** Cria a sonda real (ExoPlayer) usada pelo teste de streams; cada teste cria e libera o próprio player. */
    @OptIn(UnstableApi::class)
    fun newStreamProbe(): StreamProbe = ExoStreamProbe(this)

    /** Fila entregue pela tela de canais ao player (evita serializar milhares de canais em um Intent). */
    @Volatile var playbackQueue: ChannelQueue? = null
}
