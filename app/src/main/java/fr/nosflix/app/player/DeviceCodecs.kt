package fr.nosflix.app.player

import android.content.Context
import android.media.MediaCodecInfo
import androidx.core.content.edit
import android.media.MediaCodecList
import android.os.Build

/**
 * Formats vidéo que la puce de l'appareil décode elle-même (décodage « matériel », fluide).
 * Sans elle, VLC doit tout calculer avec le processeur : trop lourd pour l'AV1 ou le HEVC sur une TV.
 */
object DeviceCodecs {

    /** Nom du codec chez Jellyfin → type MIME Android. */
    private val Mimes = mapOf(
        "h264" to "video/avc",
        "hevc" to "video/hevc",
        "av1" to "video/av01",
        "vp9" to "video/x-vnd.on2.vp9",
    )

    private val Labels = mapOf("h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp9" to "VP9")

    /** Formats décodés par la puce vidéo. */
    val hardware: Set<String> by lazy {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
        Mimes.filter { (_, mime) ->
            infos.any { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && isHardware(info) }
        }.keys
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            !info.name.startsWith("OMX.google.", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
        }

    /** Formats déjà lus sans souci sur cet appareil (plus d'une minute de lecture). */
    @Volatile
    private var confirmed: Set<String> = emptySet()
    private const val PREFS = "player"
    private const val KEY_CONFIRMED = "confirmedCodecs"

    fun init(context: Context) {
        confirmed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_CONFIRMED, emptySet()).orEmpty()
    }

    /** Une vidéo dans ce format a bien tourné ici : on ne prévient plus pour ce format. */
    fun confirm(context: Context, codec: String?) {
        val c = codec?.lowercase() ?: return
        if (c in confirmed || c !in Mimes) return
        confirmed = confirmed + c
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_CONFIRMED, confirmed) }
    }

    /**
     * Nom du format si la lecture risque de ne pas être fluide sur cet appareil, sinon null.
     * Le H.264 se décode bien au processeur ; HEVC, AV1 et VP9 en 1080p, rarement
     * (sauf processeur assez costaud : un format qui a déjà bien tourné ici n'est plus signalé).
     */
    fun problem(codec: String?): String? {
        val c = codec?.lowercase() ?: return null
        if (c == "h264" || c !in Mimes || c in confirmed) return null
        return if (c in hardware) null else Labels[c]
    }

    /** « H.264 ✓ · HEVC ✓ · AV1 ✗ · VP9 ✓ » (pour les paramètres) ; « lu ✓ » = déjà bien lu ici sans la puce. */
    fun summary(): String = Mimes.keys.joinToString(" · ") { c ->
        Labels[c] + when {
            c in hardware -> " ✓"
            c in confirmed -> " (lu ✓)"
            else -> " ✗"
        }
    }
}
