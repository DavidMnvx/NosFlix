package fr.nosflix.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

/** Ouvre la vidéo dans un lecteur installé sur l'appareil (MX Player, GOM Player, VLC…). */
object ExternalPlayer {
    fun open(context: Context, url: String, title: String, startMs: Long) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(url.toUri(), "video/*")
            putExtra("title", title)
            // Position de départ (comprise par MX Player et VLC).
            putExtra("position", startMs.toInt())
        }
        try {
            context.startActivity(
                Intent.createChooser(view, "Lire avec…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "Aucun lecteur vidéo externe installé (MX Player, GOM Player, VLC…).",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
