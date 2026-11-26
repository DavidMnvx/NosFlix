package fr.nosflix.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class TmdbException(message: String) : Exception(message)

/** Un film ou une série renvoyé par TMDB (The Movie Database). */
data class TmdbTitle(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val year: Int?,
    val releaseDate: String?,
    val rating: Double?,
    val posterPath: String?,
    val backdropPath: String?,
    val genres: List<String>,
    val popularity: Double,
) {
    /** Clé commune avec l'index de la bibliothèque : « movie:123 » ou « tv:456 ». */
    val key: String get() = tmdbKey(id, isTv)
}

/** Où regarder un titre en France : abonnement, location, achat. */
data class WatchProviders(
    val subscription: List<String>,
    val rent: List<String>,
    val buy: List<String>,
) {
    val isEmpty: Boolean get() = subscription.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

data class CastMember(val name: String, val role: String, val photoUrl: String?)

/** Tout ce qu'il faut pour la fiche détaillée. */
data class TmdbDetails(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val overview: String,
    val tagline: String,
    val year: Int?,
    val runtimeMinutes: Int?,
    val seasons: Int?,
    val genres: List<String>,
    val rating: Double?,
    val backdropUrl: String?,
    val frenchRelease: String?,
    /** « 2026-12-16 » : sortie en salle en France (film) ou première diffusion (série). */
    val releaseIso: String?,
    /** Série : date du prochain épisode (« 2027-03-12 ») et son numéro (« S3 É1 »). */
    val nextAirDate: String?,
    val nextEpisode: String?,
    /** Série : « Returning Series », « Ended »… (TMDB). */
    val status: String?,
    val directors: List<String>,
    val cast: List<CastMember>,
    val trailerKey: String?,
    val providers: WatchProviders?,
    val collectionId: Int?,
    val collectionName: String?,
    val recommendations: List<TmdbTitle>,
)

fun tmdbKey(id: Int, isTv: Boolean): String = (if (isTv) "tv:" else "movie:") + id

/**
 * Client TMDB : tendances, cinéma, recommandations, recherche et fiches détaillées.
 * La clé (gratuite) se met dans local.properties : `tmdb.apiKey=...` (clé v3 ou jeton v4).
 */
class TmdbClient(key: String) {

    /** Clé de l’API TMDB : celle de local.properties, sinon celle collée dans les Paramètres. */
    @Volatile
    var key: String = key.trim()
        set(value) {
            field = value.trim()
        }

    val enabled: Boolean get() = key.isNotBlank()

    /** Un jeton v4 est long (JWT) et passe dans l'en-tête ; une clé v3 passe dans l'adresse. */
    private val useBearer: Boolean get() = key.length > 40

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- Listes

    /** Le classement mondial de la semaine (films ou séries), en français. */
    suspend fun trending(isTv: Boolean, limit: Int = 10): List<TmdbTitle> =
        list("/trending/${if (isTv) "tv" else "movie"}/week", mapOf("language" to LANG), isTv).take(limit)

    /** Films actuellement à l'affiche en France. */
    suspend fun nowPlaying(limit: Int = 20): List<TmdbTitle> =
        list("/movie/now_playing", mapOf("language" to LANG, "region" to REGION), isTv = false).take(limit)

    /** Prochaines sorties au cinéma en France (dates à venir uniquement). */
    suspend fun upcoming(limit: Int = 20): List<TmdbTitle> {
        val today = LocalDate.now().toString()
        return list("/movie/upcoming", mapOf("language" to LANG, "region" to REGION), isTv = false)
            .filter { (it.releaseDate ?: "") >= today }
            .sortedBy { it.releaseDate }
            .take(limit)
    }

    suspend fun recommendations(id: Int, isTv: Boolean): List<TmdbTitle> =
        list("/${if (isTv) "tv" else "movie"}/$id/recommendations", mapOf("language" to LANG), isTv)

    /** Recherche de films et séries par titre. */
    suspend fun search(query: String): List<TmdbTitle> =
        list("/search/multi", mapOf("language" to LANG, "query" to query, "include_adult" to "false"), isTv = null)

    /** Les films d'une saga, dans l'ordre de sortie. */
    suspend fun collection(id: Int): List<TmdbTitle> {
        val parts = get("/collection/$id", mapOf("language" to LANG)).optJSONArray("parts") ?: return emptyList()
        return parseList(parts, isTv = false).sortedBy { it.releaseDate ?: "9999" }
    }

    // ---------------------------------------------------------------- Fiche détaillée

    /** [light] : seulement dates et plateformes (mise à jour de la liste « À voir plus tard »). */
    suspend fun details(id: Int, isTv: Boolean, light: Boolean = false): TmdbDetails {
        val append = when {
            light && isTv -> "watch/providers"
            light -> "watch/providers,release_dates"
            isTv -> "aggregate_credits,videos,recommendations,watch/providers"
            else -> "credits,videos,recommendations,watch/providers,release_dates"
        }
        val o = get(
            "/${if (isTv) "tv" else "movie"}/$id",
            mapOf("language" to LANG, "append_to_response" to append, "include_video_language" to "fr,en,null"),
        )
        val date = o.optString(if (isTv) "first_air_date" else "release_date")
        val runtime = if (isTv) {
            o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        } else {
            o.optInt("runtime").takeIf { it > 0 }
        }
        val directors = if (isTv) {
            o.optJSONArray("created_by").objects().mapNotNull { it.optString("name").ifBlank { null } }
        } else {
            o.optJSONObject("credits")?.optJSONArray("crew").objects()
                .filter { it.optString("job") == "Director" }
                .mapNotNull { it.optString("name").ifBlank { null } }
        }.distinct()
        val castArray = if (isTv) o.optJSONObject("aggregate_credits")?.optJSONArray("cast") else o.optJSONObject("credits")?.optJSONArray("cast")
        val cast = castArray.objects().take(15).mapNotNull { c ->
            val name = c.optString("name").ifBlank { return@mapNotNull null }
            val role = if (isTv) c.optJSONArray("roles").objects().firstOrNull()?.optString("character").orEmpty() else c.optString("character")
            CastMember(name, role.trim(), image(c.optString("profile_path"), "w185"))
        }
        val collection = if (isTv) null else o.optJSONObject("belongs_to_collection")
        val frIso = if (isTv) null else frenchReleaseIso(o.optJSONObject("release_dates"))
        val next = if (isTv) o.optJSONObject("next_episode_to_air") else null
        return TmdbDetails(
            id = id,
            isTv = isTv,
            title = o.optString(if (isTv) "name" else "title").ifBlank { o.optString(if (isTv) "original_name" else "original_title") },
            overview = o.optString("overview").trim(),
            tagline = o.optString("tagline").trim(),
            year = date.take(4).toIntOrNull(),
            runtimeMinutes = runtime,
            seasons = if (isTv) o.optInt("number_of_seasons").takeIf { it > 0 } else null,
            genres = o.optJSONArray("genres").objects().mapNotNull { it.optString("name").ifBlank { null } },
            rating = o.optDouble("vote_average").takeUnless { it.isNaN() || it <= 0.0 },
            backdropUrl = image(o.optString("backdrop_path"), "w1280"),
            frenchRelease = longDate(frIso),
            releaseIso = (frIso ?: date).takeIf { it.length >= 10 }?.take(10),
            nextAirDate = next?.optString("air_date")?.takeIf { it.length >= 10 }?.take(10),
            nextEpisode = next?.let { n ->
                val s = n.optInt("season_number", -1)
                val e = n.optInt("episode_number", -1)
                if (s >= 0 && e > 0) "S$s É$e" else null
            },
            status = if (isTv) o.optString("status").ifBlank { null } else null,
            directors = directors,
            cast = cast,
            trailerKey = trailer(o.optJSONObject("videos")),
            providers = providers(o.optJSONObject("watch/providers")),
            collectionId = collection?.optInt("id")?.takeIf { it > 0 },
            collectionName = collection?.optString("name")?.ifBlank { null },
            recommendations = parseList(o.optJSONObject("recommendations")?.optJSONArray("results"), isTv),
        )
    }

    private fun trailer(videos: JSONObject?): String? {
        val all = videos?.optJSONArray("results").objects()
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        val best = all.firstOrNull { it.optString("type") == "Trailer" && it.optString("iso_639_1") == "fr" }
            ?: all.firstOrNull { it.optString("type") == "Trailer" }
            ?: all.firstOrNull { it.optString("type") == "Teaser" }
        return best?.optString("key")
    }

    private fun providers(block: JSONObject?): WatchProviders? {
        val fr = block?.optJSONObject("results")?.optJSONObject(REGION) ?: return null
        fun names(key: String) = fr.optJSONArray(key).objects().mapNotNull { it.optString("provider_name").ifBlank { null } }
        return WatchProviders(subscription = names("flatrate"), rent = names("rent"), buy = names("buy"))
    }

    /** Date de sortie en salle en France (« 2026-12-16 »), ou null si TMDB ne la connaît pas. */
    private fun frenchReleaseIso(block: JSONObject?): String? {
        val fr = block?.optJSONArray("results").objects().firstOrNull { it.optString("iso_3166_1") == REGION } ?: return null
        val dates = fr.optJSONArray("release_dates").objects()
        val theatrical = dates.firstOrNull { it.optInt("type") == 3 } ?: dates.firstOrNull() ?: return null
        return theatrical.optString("release_date").takeIf { it.length >= 10 }?.take(10)
    }

    // ---------------------------------------------------------------- HTTP et JSON

    private suspend fun list(path: String, params: Map<String, String>, isTv: Boolean?): List<TmdbTitle> =
        parseList(get(path, params).optJSONArray("results"), isTv)

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        if (!enabled) throw TmdbException("Clé TMDB manquante.")
        val builder = "$BASE$path".toHttpUrl().newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        if (!useBearer) builder.addQueryParameter("api_key", key)
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json")
            .apply { if (useBearer) header("Authorization", "Bearer $key") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw TmdbException(if (response.code == 401) "Clé TMDB refusée." else "TMDB est indisponible (${response.code}).")
            }
            JSONObject(text)
        }
    }

    /** [isTv] null : le type est lu dans chaque résultat (recherche « multi »). */
    private fun parseList(results: JSONArray?, isTv: Boolean?): List<TmdbTitle> =
        results.objects().mapNotNull { o ->
            val tv = when (isTv) {
                null -> when (o.optString("media_type")) {
                    "tv" -> true
                    "movie" -> false
                    else -> return@mapNotNull null
                }
                else -> isTv
            }
            parseTitle(o, tv)
        }

    private fun parseTitle(o: JSONObject, isTv: Boolean): TmdbTitle? {
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val title = o.optString(if (isTv) "name" else "title")
            .ifBlank { o.optString(if (isTv) "original_name" else "original_title") }
            .ifBlank { return null }
        val date = o.optString(if (isTv) "first_air_date" else "release_date").takeIf { it.length >= 4 }
        val genreIds = o.optJSONArray("genre_ids")
        val genres = if (genreIds == null) emptyList() else (0 until genreIds.length()).mapNotNull { GENRES[genreIds.optInt(it)] }.distinct()
        return TmdbTitle(
            id = id,
            isTv = isTv,
            title = title,
            originalTitle = o.optString(if (isTv) "original_name" else "original_title"),
            overview = o.optString("overview").trim(),
            year = date?.take(4)?.toIntOrNull(),
            releaseDate = date,
            rating = o.optDouble("vote_average").takeUnless { it.isNaN() || it <= 0.0 },
            posterPath = o.optString("poster_path").takeIf { it.startsWith("/") },
            backdropPath = o.optString("backdrop_path").takeIf { it.startsWith("/") },
            genres = genres,
            popularity = o.optDouble("popularity").takeUnless { it.isNaN() } ?: 0.0,
        )
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMAGES = "https://image.tmdb.org/t/p"
        private const val LANG = "fr-FR"
        private const val REGION = "FR"

        fun image(path: String?, size: String): String? = path?.takeIf { it.startsWith("/") }?.let { "$IMAGES/$size$it" }

        /** « 2026-12-16 » → « 16 décembre 2026 ». */
        fun longDate(iso: String?): String? = runCatching {
            LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
        }.getOrNull()

        /** « 2026-12-16 » → « 16 DÉC. » pour les badges. */
        fun shortDate(iso: String?): String? = runCatching {
            LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)).uppercase(Locale.FRENCH)
        }.getOrNull()

        /** Genres TMDB (films et séries), en français. */
        private val GENRES = mapOf(
            28 to "Action", 12 to "Aventure", 16 to "Animation", 35 to "Comédie", 80 to "Crime",
            99 to "Documentaire", 18 to "Drame", 10751 to "Familial", 14 to "Fantastique", 36 to "Histoire",
            27 to "Horreur", 10402 to "Musique", 9648 to "Mystère", 10749 to "Romance", 878 to "Science-fiction",
            10770 to "Téléfilm", 53 to "Thriller", 10752 to "Guerre", 37 to "Western",
            10759 to "Action", 10762 to "Jeunesse", 10763 to "Info", 10764 to "Téléréalité",
            10765 to "Science-fiction", 10766 to "Feuilleton", 10767 to "Talk-show", 10768 to "Guerre",
        )
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Carte affichable pour un titre TMDB absent de la bibliothèque (on peut le découvrir, pas le lire). */
fun TmdbTitle.toExternalItem(badge: String? = null): MediaItem = externalMediaItem(
    tmdbId = id,
    isTv = isTv,
    title = title,
    overview = overview,
    year = year,
    rating = rating,
    genres = genres,
    poster = TmdbClient.image(posterPath, "w342"),
    backdrop = TmdbClient.image(backdropPath, "w780"),
    badge = badge,
)

fun TmdbTitle.toWishItem(): WishItem = WishItem(
    tmdbId = id,
    isTv = isTv,
    title = title,
    year = year,
    overview = overview,
    rating = rating,
    genres = genres,
    poster = TmdbClient.image(posterPath, "w342"),
    backdrop = TmdbClient.image(backdropPath, "w780"),
)
