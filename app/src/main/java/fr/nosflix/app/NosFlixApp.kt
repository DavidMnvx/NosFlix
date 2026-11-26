package fr.nosflix.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import fr.nosflix.app.audio.AudioEngine
import fr.nosflix.app.data.AudioApi
import fr.nosflix.app.data.JellyfinClient
import fr.nosflix.app.player.DeviceCodecs
import fr.nosflix.app.data.ServerRoute
import fr.nosflix.app.data.SessionStore
import fr.nosflix.app.data.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NosFlixApp : Application(), SingletonImageLoader.Factory {

    lateinit var store: SessionStore
        private set

    lateinit var client: JellyfinClient
        private set

    /** Tendances mondiales : actif seulement si une clé TMDB est dans local.properties. */
    val tmdb = TmdbClient(BuildConfig.TMDB_API_KEY)

    /** Pour les envois qui doivent survivre à la fermeture d'un écran (fin de lecture, etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Musique et livres audio : appels au serveur et lecteur partagé. */
    lateinit var audioApi: AudioApi
        private set

    val audio: AudioEngine by lazy { AudioEngine(this) }

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        client = JellyfinClient(deviceId = store.deviceId, deviceName = Build.MODEL ?: "Android")
        client.session = store.load()
        client.serverId = store.serverId
        ServerRoute.away = store.awayServer
        if (!tmdb.enabled) tmdb.key = store.tmdbKey
        client.onRoute = { _, id -> if (store.serverId != id) store.serverId = id }
        audioApi = AudioApi(client)
        DeviceCodecs.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(250)
            .build()
}
