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
 * Profils enfants et titres autorisés, rangés sur le serveur de la maison (service NosFlix, port 8097) :
 * les mêmes sur le projecteur et le téléphone. Seul un compte parent (administrateur) peut les changer.
 */
class KidsApi(private val client: JellyfinClient) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): KidsConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url())
            .header("Authorization", client.authorization())
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Liste des profils enfants indisponible (${r.code}).")
            kidsFromJson(JSONObject(r.body.string()))
        }
    }

    /** [adminToken] : jeton d'un compte parent de l'appareil. */
    suspend fun save(kids: KidsConfig, adminToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url())
            .header("Authorization", client.authorization(adminToken))
            .put(kids.toJson().toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 403) throw IOException("Seul un parent peut changer les profils enfants.")
            if (!r.isSuccessful) throw IOException("Le serveur n’a pas pris la liste (${r.code}).")
        }
    }

    private fun url(): String =
        (Updater.baseUrl(client.current.server) ?: throw IOException("Adresse du serveur inconnue.")) + "/kids"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun KidsConfig.toJson(): JSONObject = JSONObject()
    .put("users", JSONArray(users.sorted()))
    .put("allowed", JSONArray(allowed.sorted()))
    .put("blocked", JSONArray(blocked.sorted()))

internal fun kidsFromJson(o: JSONObject): KidsConfig = KidsConfig(
    users = o.optJSONArray("users").toStrings().toSet(),
    allowed = o.optJSONArray("allowed").toStrings().toSet(),
    blocked = o.optJSONArray("blocked").toStrings().toSet(),
)
