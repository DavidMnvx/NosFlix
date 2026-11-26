package fr.nosflix.app.data

import fr.nosflix.app.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Liste « À voir plus tard » rangée sur le serveur de la maison (service NosFlix, port 8097) :
 * la même sur le projecteur et le téléphone. Le serveur vérifie le jeton Jellyfin
 * et réécrit le fichier A-VOIR.txt à chaque changement.
 */
class WishlistApi(private val client: JellyfinClient) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * La liste du profil [token] sur le serveur, ou null s'il n'en a pas encore.
     * Lève une exception si le serveur ne répond pas.
     */
    suspend fun fetch(token: String): List<WishItem>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url())
            .header("Authorization", client.authorization(token))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) throw IOException("Liste du serveur indisponible (${r.code}).")
            wishesFromJson(JSONObject(r.body.string()).optJSONArray("items"))
        }
    }

    /** Envoie la liste complète du profil [token]. */
    suspend fun push(items: List<WishItem>, token: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("items", items.toJsonArray()).toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(url())
            .header("Authorization", client.authorization(token))
            .put(body)
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Le serveur n’a pas pris la liste (${r.code}).")
        }
    }

    /** Adresse du fichier lisible partout : http://<serveur>:8097/A-VOIR.txt */
    fun textUrl(): String? = client.session?.server?.let { Updater.baseUrl(it) }?.let { "$it/A-VOIR.txt" }

    private fun url(): String =
        (Updater.baseUrl(client.current.server) ?: throw IOException("Adresse du serveur inconnue.")) + "/wishlist"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

// ---------------------------------------------------------------- JSON (appareil et serveur)

internal fun WishItem.toJson(): JSONObject = JSONObject()
    .put("id", tmdbId)
    .put("tv", isTv)
    .put("title", title)
    .apply { year?.let { put("year", it) } }
    .put("overview", overview)
    .apply { rating?.let { put("rating", it) } }
    .put("genres", JSONArray(genres))
    .put("poster", poster.orEmpty())
    .put("backdrop", backdrop.orEmpty())
    .put("added", addedAt)
    .apply { releaseDate?.let { put("release", it) } }
    .apply { nextAirDate?.let { put("nextAir", it) } }
    .apply { nextEpisode?.let { put("nextEp", it) } }
    .apply { status?.let { put("status", it) } }
    .apply {
        providers?.let { p ->
            put(
                "providers",
                JSONObject()
                    .put("subscription", JSONArray(p.subscription))
                    .put("rent", JSONArray(p.rent))
                    .put("buy", JSONArray(p.buy)),
            )
        }
    }
    .put("checked", checkedAt)
    .put("inLibrary", inLibrary)

internal fun List<WishItem>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }

internal fun wishesFromJson(arr: JSONArray?): List<WishItem> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::wishFromJson) }.distinctBy { it.key }
}

private fun wishFromJson(o: JSONObject): WishItem? {
    val id = o.optInt("id").takeIf { it > 0 } ?: return null
    val p = o.optJSONObject("providers")
    return WishItem(
        tmdbId = id,
        isTv = o.optBoolean("tv"),
        title = o.optString("title"),
        year = if (o.has("year")) o.optInt("year") else null,
        overview = o.optString("overview"),
        rating = if (o.has("rating")) o.optDouble("rating").takeUnless { it.isNaN() } else null,
        genres = o.optJSONArray("genres").toStrings(),
        poster = o.optString("poster").ifBlank { null },
        backdrop = o.optString("backdrop").ifBlank { null },
        addedAt = o.optLong("added"),
        releaseDate = o.optString("release").ifBlank { null },
        nextAirDate = o.optString("nextAir").ifBlank { null },
        nextEpisode = o.optString("nextEp").ifBlank { null },
        status = o.optString("status").ifBlank { null },
        providers = p?.let { WatchProviders(it.optJSONArray("subscription").toStrings(), it.optJSONArray("rent").toStrings(), it.optJSONArray("buy").toStrings()) },
        checkedAt = o.optLong("checked"),
        inLibrary = o.optBoolean("inLibrary"),
    )
}
