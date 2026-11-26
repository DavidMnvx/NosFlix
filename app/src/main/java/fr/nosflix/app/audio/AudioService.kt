package fr.nosflix.app.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import fr.nosflix.app.MainActivity
import fr.nosflix.app.NosFlixApp

/**
 * Service de lecture audio : garde la musique ou le livre audio en marche écran éteint,
 * avec la notification et les commandes de l'écran de verrouillage (Media3).
 */
class AudioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as NosFlixApp).audio
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, engine.sessionPlayer())
            .setSessionActivity(pending)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** L'appli est fermée depuis les applis récentes : on s'arrête si rien ne joue. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Le lecteur appartient à AudioEngine : on ne libère que la session.
        session?.release()
        session = null
        super.onDestroy()
    }
}
