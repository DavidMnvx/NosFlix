package fr.nosflix.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.videolan.libvlc.MediaPlayer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Une piste audio ou de sous-titres proposée par VLC. */
data class TrackOption(val id: Int, val name: String)

/** Les panneaux d'options du lecteur, comme dans VLC. */
enum class PlayerPanel(val title: String, val short: String) {
    Audio("Audio", "Audio"),
    Subtitles("Sous-titres", "Sous-titres"),
    Video("Format de l’image", "Format"),
    Speed("Vitesse de lecture", "Vitesse"),
    Equalizer("Égaliseur", "Égaliseur"),
}

/** Format de l'image (les « ratios » de VLC). */
enum class VideoFormat(val label: String, val scale: MediaPlayer.ScaleType) {
    BestFit("Ajusté à l’écran", MediaPlayer.ScaleType.SURFACE_BEST_FIT),
    Zoom("Zoom (remplit l’écran sans déformer)", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN),
    Stretch("Étiré (remplit l’écran)", MediaPlayer.ScaleType.SURFACE_FILL),
    Wide("16:9", MediaPlayer.ScaleType.SURFACE_16_9),
    Classic("4:3", MediaPlayer.ScaleType.SURFACE_4_3),
    Original("Taille d’origine", MediaPlayer.ScaleType.SURFACE_ORIGINAL),
}

val PlayerSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

const val EQ_OFF = -1
const val EQ_CUSTOM = -2

/** État de l'écran de lecture, partagé entre l'activité et l'interface Compose. */
class PlayerUiState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var timeMs by mutableLongStateOf(0L)
    var lengthMs by mutableLongStateOf(0L)
    var controlsVisible by mutableStateOf(true)
    var poke by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)

    /** TV : la rangée de boutons (Audio, Sous-titres…) a le focus de la télécommande. */
    var toolbarActive by mutableStateOf(false)
    var panel by mutableStateOf<PlayerPanel?>(null)

    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var audioTrack by mutableIntStateOf(-1)
    var audioDelayMs by mutableLongStateOf(0L)

    var subtitleTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var subtitleTrack by mutableIntStateOf(-1)
    var subtitleDelayMs by mutableLongStateOf(0L)

    var format by mutableStateOf(VideoFormat.BestFit)
    var rate by mutableFloatStateOf(1f)

    var eqPreset by mutableIntStateOf(EQ_OFF)
    var eqPreamp by mutableFloatStateOf(0f)
    var eqBands by mutableStateOf<List<Float>>(emptyList())
    var eqPresetNames by mutableStateOf<List<String>>(emptyList())
    var eqFrequencies by mutableStateOf<List<Float>>(emptyList())

    /** « Passer l'intro » / « Passer le récap » (null = caché). */
    var skipLabel by mutableStateOf<String?>(null)

    /** Épisode suivant : son nom, et le compte à rebours avant de le lancer (-1 = caché). */
    var nextLabel by mutableStateOf<String?>(null)
    var nextCountdown by mutableIntStateOf(-1)

    fun showControls() {
        controlsVisible = true
        poke++
    }
}

/** Ce que l'interface du lecteur peut demander à l'activité. */
interface PlayerActions {
    fun togglePause()
    fun seekTo(ms: Long)
    fun close()
    fun openExternal()
    fun openPanel(panel: PlayerPanel?)
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)
    fun shiftAudioDelay(deltaMs: Long)
    fun resetAudioDelay()
    fun shiftSubtitleDelay(deltaMs: Long)
    fun resetSubtitleDelay()
    fun setFormat(format: VideoFormat)
    fun setRate(rate: Float)
    fun setEqPreset(index: Int)
    fun shiftEqBand(band: Int, deltaDb: Float)
    fun shiftEqPreamp(deltaDb: Float)
    fun skipSegment()
    fun playNextNow()
    fun cancelNext()
}

// ---------------------------------------------------------------- Textes

private val PresetNames = mapOf(
    "flat" to "Neutre",
    "classical" to "Classique",
    "club" to "Club",
    "dance" to "Dance",
    "full bass" to "Basses renforcées",
    "full bass and treble" to "Basses et aigus",
    "full treble" to "Aigus renforcés",
    "headphones" to "Casque",
    "large hall" to "Grande salle",
    "live" to "Concert",
    "party" to "Soirée",
    "pop" to "Pop",
    "reggae" to "Reggae",
    "rock" to "Rock",
    "ska" to "Ska",
    "soft" to "Doux",
    "soft rock" to "Soft rock",
    "techno" to "Techno",
)

fun presetLabel(raw: String?): String {
    val name = raw?.trim().orEmpty()
    return PresetNames[name.lowercase()] ?: name.ifBlank { "Préréglage" }
}

fun trackLabel(raw: String?, fallback: String): String {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return fallback
    if (name.equals("Disable", ignoreCase = true)) return "Désactivé"
    return name.replace(Regex("^Track", RegexOption.IGNORE_CASE), "Piste")
}

fun speedLabel(rate: Float): String =
    if (abs(rate - 1f) < 0.01f) "Normale (1×)" else String.format(Locale.FRANCE, "%s×", trimNumber(rate))

fun delayLabel(ms: Long): String = when {
    ms == 0L -> "0 ms"
    abs(ms) >= 1000 -> (if (ms > 0) "+" else "−") + String.format(Locale.FRANCE, "%.2f s", abs(ms) / 1000.0)
    else -> (if (ms > 0) "+" else "−") + "${abs(ms)} ms"
}

fun dbLabel(db: Float): String {
    val v = db.roundToInt()
    return when {
        v > 0 -> "+$v dB"
        v < 0 -> "−${-v} dB"
        else -> "0 dB"
    }
}

fun frequencyLabel(hz: Float): String =
    if (hz < 1000f) "${hz.roundToInt()} Hz" else "${trimNumber(hz / 1000f)} kHz"

private fun trimNumber(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString().replace('.', ',').trimEnd('0')
}
