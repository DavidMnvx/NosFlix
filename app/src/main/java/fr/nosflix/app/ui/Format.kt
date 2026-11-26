package fr.nosflix.app.ui

import fr.nosflix.app.data.MediaItem
import java.util.Locale

/** « 1 h 52 », « 45 min » ou « 3 saisons ». */
fun MediaItem.durationLabel(): String? = when {
    isSeries -> childCount?.takeIf { it > 0 }?.let { if (it > 1) "$it saisons" else "1 saison" }
    else -> runtimeMinutes?.takeIf { it > 0 }?.let { m ->
        if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
    }
}

fun MediaItem.ratingLabel(): String? =
    rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.FRANCE, "%.1f", it) }

/** « 2023 · 1 h 54 · Science-fiction, Thriller ». */
fun MediaItem.metaLine(): String =
    listOfNotNull(
        year?.toString(),
        durationLabel(),
        genres.take(3).joinToString(", ").ifBlank { null },
    ).joinToString(" · ")

/** « S2 · É4 · Titre de l'épisode » pour un épisode, sinon null. */
fun MediaItem.episodeLine(): String? {
    if (!isEpisode) return null
    val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "É$it" }).joinToString(" · ")
    return listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")
}

fun MediaItem.remainingLabel(): String? {
    val total = runTimeTicks ?: return null
    if (!inProgress) return null
    val minutes = ((total - positionTicks) / MediaItem.TICKS_PER_MINUTE).coerceAtLeast(1)
    return "reste $minutes min"
}

fun MediaItem.asideLabel(): String? = remainingLabel() ?: ratingLabel()

fun MediaItem.newBadgeLabel(): String = if (isSeries) "NOUVELLE SÉRIE" else "NOUVEAU FILM"

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
