package fr.nosflix.app.player

import android.content.Context
import androidx.core.content.edit
import fr.nosflix.app.NosFlixApp
import fr.nosflix.app.data.StreamLang

/** Langue audio et sous-titres choisis automatiquement au lancement d'une vidéo. */
enum class AudioPref(val label: String) {
    French("Français"),
    Original("Version originale"),
    English("Anglais"),
}

enum class SubtitlePref(val label: String, val hint: String) {
    Auto("Automatiques", "en français quand l’audio n’est pas en français"),
    French("Toujours en français", "dès qu’il y en a"),
    Off("Désactivés", "jamais au démarrage"),
}

/** Choix gardés pour chaque profil (sinon le réglage commun de l'appareil). */
object LanguagePrefs {
    private const val FILE = "player"
    private const val KEY_AUDIO = "audioLang"
    private const val KEY_SUBS = "subtitleMode"

    fun audio(context: Context): AudioPref =
        runCatching { AudioPref.valueOf(read(context, KEY_AUDIO) ?: AudioPref.French.name) }.getOrDefault(AudioPref.French)

    fun subtitles(context: Context): SubtitlePref =
        runCatching { SubtitlePref.valueOf(read(context, KEY_SUBS) ?: SubtitlePref.Auto.name) }.getOrDefault(SubtitlePref.Auto)

    fun setAudio(context: Context, value: AudioPref) = prefs(context).edit { putString(profileKey(context, KEY_AUDIO), value.name) }

    fun setSubtitles(context: Context, value: SubtitlePref) = prefs(context).edit { putString(profileKey(context, KEY_SUBS), value.name) }

    private fun read(context: Context, key: String): String? =
        prefs(context).getString(profileKey(context, key), null) ?: prefs(context).getString(key, null)

    private fun profileKey(context: Context, key: String): String {
        val userId = (context.applicationContext as? NosFlixApp)?.client?.session?.userId
        return if (userId == null) key else "$key:$userId"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Reconnaître une langue

    private val FrenchWords = Regex("(?i)\\b(fr|fre|fra|french|français|francais|vff|vfq|vfi|vf|truefrench)\\b")
    private val EnglishWords = Regex("(?i)\\b(en|eng|english|anglais)\\b")

    /** La piste est-elle en français ? D'après la langue donnée par Jellyfin, sinon d'après son nom. */
    fun isFrench(lang: String?, name: String?): Boolean =
        lang?.lowercase()?.let { it == "fr" || it == "fre" || it == "fra" } == true || (name != null && FrenchWords.containsMatchIn(name))

    fun isEnglish(lang: String?, name: String?): Boolean =
        lang?.lowercase()?.let { it == "en" || it == "eng" } == true || (name != null && EnglishWords.containsMatchIn(name))

    fun isForced(stream: StreamLang?, name: String?): Boolean =
        stream?.forced == true || (name != null && Regex("(?i)forc").containsMatchIn(name))
}
