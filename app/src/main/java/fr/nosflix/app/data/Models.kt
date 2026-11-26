package fr.nosflix.app.data

/** Connexion enregistrée à un serveur Jellyfin. */
data class Session(
    val server: String,
    val userId: String,
    val token: String,
    val userName: String,
)

/** Un film, une série ou un épisode renvoyé par Jellyfin. */
data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String,
    val year: Int?,
    val runTimeTicks: Long?,
    val rating: Double?,
    val genres: List<String>,
    val dateCreatedMs: Long?,
    val seriesId: String?,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val childCount: Int?,
    val playedPercentage: Double?,
    val positionTicks: Long,
    val played: Boolean,
    val favorite: Boolean,
    val posterItemId: String,
    val backdropItemId: String?,
    val logoItemId: String?,
    /** Identifiant TMDB (sert à reconnaître un film des tendances déjà dans la bibliothèque). */
    val tmdbId: Int? = null,
    /** Film des tendances mondiales absent de la bibliothèque : on peut le découvrir, pas le lire. */
    val external: Boolean = false,
    val externalPoster: String? = null,
    val externalBackdrop: String? = null,
    /** Badge des cartes hors bibliothèque : « À DÉCOUVRIR », « AU CINÉMA », « 16 DÉC. »… */
    val externalBadge: String? = null,
    /** Âge conseillé donné par Jellyfin (« FR-12 », « TV-MA »…), s'il le connaît. */
    val officialRating: String? = null,
) {
    val isSeries: Boolean get() = type == "Series"

    /** Clé TMDB commune avec les listes TMDB (« movie:123 » / « tv:456 »), si connue. */
    val tmdbKey: String? get() = tmdbId?.let { tmdbKey(it, isSeries) }
    val isEpisode: Boolean get() = type == "Episode"
    val isMovie: Boolean get() = type == "Movie"

    val runtimeMinutes: Int? get() = runTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() }

    /** Pour un épisode on affiche le nom de la série, sinon le titre. */
    val displayTitle: String get() = if (isEpisode && !seriesName.isNullOrBlank()) seriesName else name

    val isNew: Boolean
        get() = dateCreatedMs?.let { System.currentTimeMillis() - it < NEW_FOR_MS } ?: false

    val inProgress: Boolean
        get() = positionTicks > 0 && (playedPercentage ?: 0.0) in 0.5..97.0

    /** Score du tri « Pour toi » : bien noté, pas encore vu, en cours ou récent. */
    fun smartScore(): Double =
        (rating ?: 6.0) +
            (if (played) -2.5 else 0.0) +
            (if (inProgress) 1.2 else 0.0) +
            (if (isNew) 0.8 else 0.0)

    companion object {
        const val TICKS_PER_MS = 10_000L
        const val TICKS_PER_MINUTE = 600_000_000L
        private const val NEW_FOR_MS = 7L * 24 * 3600 * 1000
    }
}

/** Lien « Voir plus » d'une ligne vers l'écran de parcours. */
data class MoreLink(val key: BrowseKey, val sort: SortOption)

/** Une ligne horizontale d'affiches. */
data class MediaRow(
    val id: String,
    val title: String,
    val sub: String = "",
    val items: List<MediaItem>,
    val top: Boolean = false,
    val more: MoreLink? = null,
)

/** Ce que l'on parcourt : une section de la bibliothèque ou un genre. */
sealed class BrowseKey(val title: String, val kicker: String, val types: String) {
    data object Movies : BrowseKey("Films", "BIBLIOTHÈQUE", "Movie")
    data object Shows : BrowseKey("Séries", "BIBLIOTHÈQUE", "Series")
    data object MyList : BrowseKey("Ma liste", "BIBLIOTHÈQUE", "Movie,Series")
    data object All : BrowseKey("Toute la bibliothèque", "BIBLIOTHÈQUE", "Movie,Series")
    data object Uhd : BrowseKey("Films en 4K", "QUALITÉ", "Movie")
    /** Un genre : films et séries, ou seulement les films (« Movie ») ou les séries (« Series »). */
    data class Genre(val name: String, val scope: String = "Movie,Series") : BrowseKey(
        name,
        when (scope) {
            "Movie" -> "FILMS"
            "Series" -> "SÉRIES"
            else -> "GENRE"
        },
        scope,
    )

    /** « Voir tout » d'une ligne de Films ou Séries (tous, pas encore vus, courts) : la grille complète. */
    data class Section(val label: String, val scope: String, val filter: BrowseFilter) : BrowseKey(
        label,
        if (scope == "Movie") "FILMS" else "SÉRIES",
        scope,
    )
}

enum class BrowseFilter { All, Unseen, Short }

enum class SortOption(val label: String, val sortBy: String, val order: String) {
    Smart("Pour toi", "CommunityRating", "Descending"),
    Top("Mieux notés", "CommunityRating", "Descending"),
    Recent("Ajoutés récemment", "DateCreated", "Descending"),
    Year("Plus récents", "ProductionYear,PremiereDate", "Descending"),
    Short("Plus courts", "Runtime", "Ascending"),
    Az("A → Z", "SortName", "Ascending"),
}

sealed interface MainScreen {
    data object Home : MainScreen
    data object Browse : MainScreen
    data object Search : MainScreen
    data object Detail : MainScreen
    data object Music : MainScreen
    /** Un album, une playlist ou les titres likés. */
    data object MusicCollection : MainScreen
    data object Books : MainScreen
    data object Book : MainScreen
    /** Actualité : sorties ciné, tendances, recommandations (TMDB). */
    data object News : MainScreen
}

sealed interface HomeState {
    data object Loading : HomeState
    data class Error(val message: String) : HomeState
    /** [rows] : l'accueil (ta bibliothèque) ; [news] : l'onglet Actualité. */
    data class Ready(val hero: MediaItem?, val rows: List<MediaRow>, val news: List<MediaRow> = emptyList()) : HomeState
}

data class BrowseState(
    val key: BrowseKey,
    val sort: SortOption,
    val loading: Boolean = true,
    val rows: List<MediaRow> = emptyList(),
    val count: String = "",
    val error: String? = null,
)

data class SearchHit(val item: MediaItem, val caption: String)

/** Fiche détaillée ouverte. */
data class DetailState(
    /** Ce qu'on affiche : la version de la bibliothèque si le titre y est. */
    val item: MediaItem,
    /** Le titre est dans la bibliothèque : on peut le lire. */
    val playable: Boolean,
    val loading: Boolean = true,
    val info: TmdbDetails? = null,
    val collection: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    /** Série : saisons, saison affichée et ses épisodes, prochain épisode à voir. */
    val seasons: List<SeasonInfo> = emptyList(),
    val seasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val episodesLoading: Boolean = false,
    val nextUp: MediaItem? = null,
)

/** Un profil = un compte Jellyfin (son historique, sa reprise, sa liste d'envies). */
data class Profile(val id: String, val name: String, val isAdmin: Boolean, val hasPassword: Boolean)

/**
 * Profils enfants et titres choisis par les parents (rangés sur le serveur de la maison).
 * Un enfant voit : les titres [allowed], plus ceux reconnus tous publics, moins ceux [blocked].
 */
data class KidsConfig(
    val users: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
) {
    fun allows(item: MediaItem): Boolean = item.id !in blocked && (item.id in allowed || autoKid(item))

    companion object {
        /** Classements américains faits pour les enfants. */
        private val KID_RATINGS = setOf("G", "TV-Y", "TV-Y7", "TV-G")
        /** « Tous publics » en France : large (beaucoup de films adultes), donc seulement avec un genre enfant. */
        private val ALL_AGES = setOf("FR-U", "FR-TP", "FR-0", "U", "TP", "0", "AL")
        private val UNRATED = setOf("FR-NR", "NR", "NOT RATED", "UNRATED")
        private val KID_GENRES = setOf("animation", "familial", "family", "jeunesse", "kids", "enfants")

        /** Pré-coché : dessin animé ou film familial tous publics (ou sans âge conseillé). */
        fun autoKid(item: MediaItem): Boolean {
            val rating = item.officialRating?.trim()?.uppercase().orEmpty()
            if (rating in KID_RATINGS) return true
            val kidGenre = item.genres.any { it.lowercase() in KID_GENRES }
            return kidGenre && (rating.isEmpty() || rating in UNRATED || rating in ALL_AGES)
        }
    }
}

/** Une saison ; [played] : tous ses épisodes sont vus. */
data class SeasonInfo(val id: String, val name: String, val index: Int?, val played: Boolean = false)

/** « Tout marquer comme vu ? » : changement en bloc (série, saison) à confirmer. */
class PlayedConfirm(val title: String, val message: String, val confirmLabel: String, val action: () -> Unit)

/** Le même titre, marqué vu ou pas vu (Jellyfin remet alors la reprise à zéro). */
fun MediaItem.withPlayed(played: Boolean): MediaItem = copy(played = played, positionTicks = 0L, playedPercentage = null)

/** Lecture en attente de confirmation : format que l'appareil risque de mal lire. */
data class PlayWarning(val item: MediaItem, val format: String)

/**
 * Un titre gardé de côté dans la liste d'envies (« À voir plus tard »).
 * La liste est rangée sur le serveur de la maison (même liste sur tous les appareils)
 * et le serveur en tire le fichier A-VOIR.txt.
 */
data class WishItem(
    val tmdbId: Int,
    val isTv: Boolean,
    val title: String,
    val year: Int?,
    val overview: String,
    val rating: Double?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    /** Ajouté le (millisecondes). */
    val addedAt: Long = 0L,
    /** « 2026-12-16 » : sortie en salle en France (film) ou première diffusion (série). */
    val releaseDate: String? = null,
    /** Série : prochain épisode (« 2027-03-12 », « S3 É1 ») et état (« Returning Series », « Ended »…). */
    val nextAirDate: String? = null,
    val nextEpisode: String? = null,
    val status: String? = null,
    /** Où le regarder en France (abonnement, location, achat). */
    val providers: WatchProviders? = null,
    /** Dernière mise à jour des dates et plateformes depuis TMDB (millisecondes). */
    val checkedAt: Long = 0L,
    /** Déjà dans la bibliothèque Jellyfin. */
    val inLibrary: Boolean = false,
) {
    val key: String get() = tmdbKey(tmdbId, isTv)

    fun toMediaItem(badge: String? = null): MediaItem =
        externalMediaItem(tmdbId, isTv, title, overview, year, rating, genres, poster, backdrop, badge)

    /** Dates de sortie et plateformes à jour, d'après la fiche TMDB. */
    fun withDetails(info: TmdbDetails, now: Long): WishItem = copy(
        overview = overview.ifBlank { info.overview },
        rating = info.rating ?: rating,
        genres = info.genres.ifEmpty { genres },
        releaseDate = info.releaseIso ?: releaseDate,
        nextAirDate = info.nextAirDate,
        nextEpisode = info.nextEpisode,
        status = info.status,
        providers = info.providers,
        checkedAt = now,
    )
}

fun MediaItem.toWishItem(): WishItem? {
    val id = tmdbId ?: return null
    return WishItem(id, isSeries, name, year, overview, rating, genres, externalPoster, externalBackdrop)
}

/** Construit la carte d'un titre hors bibliothèque. */
fun externalMediaItem(
    tmdbId: Int,
    isTv: Boolean,
    title: String,
    overview: String,
    year: Int?,
    rating: Double?,
    genres: List<String>,
    poster: String?,
    backdrop: String?,
    badge: String?,
): MediaItem {
    val key = tmdbKey(tmdbId, isTv)
    return MediaItem(
        id = "tmdb-$key",
        name = title,
        type = if (isTv) "Series" else "Movie",
        overview = overview,
        year = year,
        runTimeTicks = null,
        rating = rating,
        genres = genres,
        dateCreatedMs = null,
        seriesId = null,
        seriesName = null,
        season = null,
        episode = null,
        childCount = null,
        playedPercentage = null,
        positionTicks = 0L,
        played = false,
        favorite = false,
        posterItemId = "tmdb-$key",
        backdropItemId = null,
        logoItemId = null,
        tmdbId = tmdbId,
        external = true,
        externalPoster = poster,
        externalBackdrop = backdrop,
        externalBadge = badge,
    )
}

fun plural(n: Int, one: String, many: String): String = "$n ${if (n > 1) many else one}"
