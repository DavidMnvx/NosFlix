package fr.nosflix.app.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class JellyfinException(message: String, val code: Int = 0) : Exception(message)

/** Fichier de sous-titres fourni par le serveur. */
data class ExternalSubtitle(val url: String, val title: String, val language: String? = null, val forced: Boolean = false)

/** Une piste audio ou de sous-titres intégrée au fichier, dans l'ordre du fichier. */
data class StreamLang(val language: String?, val title: String, val forced: Boolean, val isDefault: Boolean)

/** Passage à sauter ou à repérer : « Intro », « Recap », « Outro » (générique de fin)… */
data class Segment(val type: String, val startMs: Long, val endMs: Long)

/** Ce que le lecteur doit savoir d'une vidéo : pistes, sous-titres du serveur, génériques. */
data class PlaybackInfo(
    val audio: List<StreamLang>,
    val subtitles: List<StreamLang>,
    val external: List<ExternalSubtitle>,
    val segments: List<Segment>,
    val videoCodec: String? = null,
)

/** Petit client pour l'API REST de Jellyfin (OkHttp + org.json). */
class JellyfinClient(internal val deviceId: String, private val deviceName: String) {

    @Volatile
    var session: Session? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    internal val current: Session
        get() = session ?: throw JellyfinException("Tu n’es pas connecté.")

    // ---------------------------------------------------------------- Connexion

    suspend fun authenticate(serverInput: String, user: String, password: String): Session {
        val newSession = login(serverInput, user, password)
        session = newSession
        return newSession
    }

    /** Se connecter à un compte sans le prendre tout de suite (profils). */
    suspend fun login(serverInput: String, user: String, password: String): Session {
        val typed = normalizeServer(serverInput)
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        // Hors de la maison, l'adresse du réseau local ne répond pas : on essaie celle de Tailscale.
        val (server, text) = try {
            typed to call("POST", "$typed/Users/AuthenticateByName", body, token = null, retry = false)
        } catch (e: IOException) {
            val other = reroute(failed = typed)?.takeIf { it != typed } ?: throw e
            other to call("POST", "$other/Users/AuthenticateByName", body, token = null, retry = false)
        }
        val json = JSONObject(text)
        json.optStringOrNull("ServerId")?.let { id ->
            serverId = id
            onRoute?.invoke(server, id)
        }
        val userJson = json.getJSONObject("User")
        return Session(
            server = server,
            userId = userJson.getString("Id"),
            token = json.getString("AccessToken"),
            userName = userJson.optString("Name", user),
        )
    }

    // ---------------------------------------------------------------- Profils (comptes Jellyfin)

    /** Profil enfant : seuls ces films et séries (et leurs épisodes) sont visibles ; null = tout. */
    @Volatile
    var kidAllowed: Set<String>? = null

    /** Tous les comptes du serveur ([adminToken] : jeton d'un compte parent/administrateur). */
    suspend fun users(adminToken: String): List<Profile> {
        val arr = JSONArray(call("GET", url("/Users"), token = adminToken))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val policy = o.optJSONObject("Policy")
            if (policy?.optBoolean("IsDisabled") == true) return@mapNotNull null
            Profile(
                id = o.optString("Id").ifBlank { return@mapNotNull null },
                name = o.optString("Name"),
                isAdmin = policy?.optBoolean("IsAdministrator") == true,
                hasPassword = o.optBoolean("HasPassword"),
            )
        }
    }

    /** Nouveau profil sans mot de passe, caché de la page de connexion du site Jellyfin. */
    suspend fun createProfile(name: String, adminToken: String): String {
        val created = JSONObject(call("POST", url("/Users/New"), JSONObject().put("Name", name).toString(), token = adminToken))
        val id = created.getString("Id")
        runCatching {
            val user = JSONObject(call("GET", url("/Users/$id"), token = adminToken))
            val policy = user.getJSONObject("Policy").put("IsHidden", true)
            call("POST", url("/Users/$id/Policy"), policy.toString(), token = adminToken)
        }
        return id
    }

    /** Le jeton est-il toujours accepté par Jellyfin ? (false s'il a été révoqué) */
    suspend fun tokenValid(server: String, token: String): Boolean = try {
        call("GET", "$server/Users/Me", token = token)
        true
    } catch (e: JellyfinException) {
        if (e.code == 401 || e.code == 403) false else throw e
    }

    /** Supprime le compte Jellyfin du profil (et son historique). */
    suspend fun deleteProfile(id: String, adminToken: String) {
        call("DELETE", url("/Users/$id"), token = adminToken)
    }

    // ---------------------------------------------------------------- Catalogue

    suspend fun items(
        types: String = "Movie,Series",
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 40,
        genre: String? = null,
        filters: String? = null,
        is4K: Boolean? = null,
        search: String? = null,
        personIds: String? = null,
        /** true : sans le filtre du profil enfant (pour le calculer). */
        unfiltered: Boolean = false,
    ): List<MediaItem> {
        val params = baseParams() + mapOf(
            "IncludeItemTypes" to types,
            "Recursive" to true,
            "SortBy" to sortBy,
            "SortOrder" to sortOrder,
            "Limit" to limit,
            "Genres" to genre,
            "Filters" to filters,
            "Is4K" to is4K,
            "SearchTerm" to search,
            "PersonIds" to personIds,
        )
        return parseItems(call("GET", url("/Items", params)), unfiltered)
    }

    suspend fun resume(limit: Int = 20): List<MediaItem> {
        val params = baseParams() + mapOf("Limit" to limit, "MediaTypes" to "Video")
        return try {
            parseItems(call("GET", url("/UserItems/Resume", params)))
        } catch (e: JellyfinException) {
            if (e.code == 404) parseItems(call("GET", url("/Users/${current.userId}/Items/Resume", params))) else throw e
        }
    }

    suspend fun nextUp(limit: Int = 20, seriesId: String? = null): List<MediaItem> =
        parseItems(call("GET", url("/Shows/NextUp", baseParams() + mapOf("Limit" to limit, "SeriesId" to seriesId))))

    suspend fun firstEpisode(seriesId: String): MediaItem? =
        parseItems(call("GET", url("/Shows/$seriesId/Episodes", baseParams() + mapOf("Limit" to 1)))).firstOrNull()

    suspend fun similar(itemId: String, limit: Int = 20): List<MediaItem> =
        parseItems(call("GET", url("/Items/$itemId/Similar", baseParams() + mapOf("Limit" to limit))))

    suspend fun genres(): List<String> {
        val params = mapOf(
            "userId" to current.userId,
            "IncludeItemTypes" to "Movie,Series",
            "SortBy" to "SortName",
            "Recursive" to true,
        )
        val arr = JSONObject(call("GET", url("/Genres", params))).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("Name")?.takeIf { it.isNotBlank() }
        }
    }

    /** Recherche d'acteurs / réalisateurs : renvoie (id, nom). */
    suspend fun persons(term: String, limit: Int = 4): List<Pair<String, String>> {
        val params = mapOf("userId" to current.userId, "searchTerm" to term, "limit" to limit)
        val arr = JSONObject(call("GET", url("/Persons", params))).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("Id")
            val name = o.optString("Name")
            if (id.isBlank() || name.isBlank()) null else id to name
        }
    }

    /**
     * Marque comme vu (ou pas vu) un film, un épisode, une saison ou une série entière :
     * Jellyfin s'occupe de tous les épisodes, et la reprise en cours repart du début.
     */
    suspend fun setPlayed(itemId: String, played: Boolean) {
        val method = if (played) "POST" else "DELETE"
        try {
            call(method, url("/UserPlayedItems/$itemId", mapOf("userId" to current.userId)))
        } catch (e: JellyfinException) {
            if (e.code == 404) call(method, url("/Users/${current.userId}/PlayedItems/$itemId")) else throw e
        }
    }

    /** Un seul titre, avec son état (vu, reprise) à jour. */
    suspend fun item(itemId: String): MediaItem? =
        parseItems(call("GET", url("/Items", baseParams() + mapOf("Ids" to itemId)))).firstOrNull()

    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        val method = if (favorite) "POST" else "DELETE"
        try {
            call(method, url("/UserFavoriteItems/$itemId", mapOf("userId" to current.userId)))
        } catch (e: JellyfinException) {
            if (e.code == 404) call(method, url("/Users/${current.userId}/FavoriteItems/$itemId")) else throw e
        }
    }

    // ---------------------------------------------------------------- Images et lecture

    fun imageUrl(itemId: String?, type: String, fillWidth: Int? = null, fillHeight: Int? = null): String? {
        val s = session ?: return null
        if (itemId == null) return null
        val sb = StringBuilder(s.server).append("/Items/").append(itemId).append("/Images/").append(type).append("?quality=90")
        if (fillWidth != null) sb.append("&fillWidth=").append(fillWidth)
        if (fillHeight != null) sb.append("&fillHeight=").append(fillHeight)
        return sb.toString()
    }

    /** Lecture directe du fichier (sans conversion) : le lecteur reçoit le fichier d'origine. */
    fun streamUrl(itemId: String): String {
        val s = current
        return "${s.server}/Videos/$itemId/stream?static=true&mediaSourceId=$itemId&api_key=${s.token}"
    }

    /**
     * Sous-titres « externes » rangés à côté de la vidéo sur le serveur (fichiers .srt, .ass…).
     * Les sous-titres intégrés au fichier vidéo (MKV) sont trouvés directement par VLC.
     */
    suspend fun externalSubtitles(itemId: String): List<ExternalSubtitle> {
        val s = current
        val params = mapOf("userId" to s.userId, "Fields" to "MediaSources,MediaStreams")
        val text = try {
            call("GET", url("/Items/$itemId", params))
        } catch (e: JellyfinException) {
            if (e.code == 404) call("GET", url("/Users/${s.userId}/Items/$itemId")) else throw e
        }
        val json = JSONObject(text)
        val source = json.optJSONArray("MediaSources")?.optJSONObject(0)
        val sourceId = source?.optString("Id")?.takeIf { it.isNotBlank() } ?: itemId
        val streams = source?.optJSONArray("MediaStreams") ?: json.optJSONArray("MediaStreams") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            val stream = streams.optJSONObject(i) ?: return@mapNotNull null
            if (stream.optString("Type") != "Subtitle" || !stream.optBoolean("IsExternal")) return@mapNotNull null
            val index = stream.optInt("Index", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val format = when (val codec = stream.optString("Codec").lowercase()) {
                "ass", "ssa" -> codec
                "vtt", "webvtt" -> "vtt"
                else -> "srt"
            }
            val title = stream.optString("DisplayTitle").ifBlank { stream.optString("Language") }.ifBlank { "Sous-titres $index" }
            ExternalSubtitle(
                url = "${s.server}/Videos/$itemId/$sourceId/Subtitles/$index/Stream.$format?api_key=${s.token}",
                title = title,
            )
        }
    }

    /**
     * Pistes (langues), sous-titres rangés à côté de la vidéo et génériques (intro, générique de fin).
     * Les génériques viennent du serveur (« segments ») ou, à défaut, des chapitres du fichier.
     */
    suspend fun playbackInfo(itemId: String): PlaybackInfo {
        val s = current
        val params = mapOf("userId" to s.userId, "Fields" to "MediaSources,MediaStreams,Chapters")
        val text = try {
            call("GET", url("/Items/$itemId", params))
        } catch (e: JellyfinException) {
            if (e.code == 404) call("GET", url("/Users/${s.userId}/Items/$itemId", mapOf("Fields" to "MediaSources,MediaStreams,Chapters"))) else throw e
        }
        val json = JSONObject(text)
        val source = json.optJSONArray("MediaSources")?.optJSONObject(0)
        val sourceId = source?.optString("Id")?.takeIf { it.isNotBlank() } ?: itemId
        val streams = source?.optJSONArray("MediaStreams") ?: json.optJSONArray("MediaStreams") ?: JSONArray()
        val audio = mutableListOf<StreamLang>()
        val subtitles = mutableListOf<StreamLang>()
        val external = mutableListOf<ExternalSubtitle>()
        var videoCodec: String? = null
        for (i in 0 until streams.length()) {
            val st = streams.optJSONObject(i) ?: continue
            val lang = st.optStringOrNull("Language")
            val title = st.optString("DisplayTitle").ifBlank { st.optString("Title") }
            val forced = st.optBoolean("IsForced")
            when (st.optString("Type")) {
                "Video" -> if (videoCodec == null) videoCodec = st.optStringOrNull("Codec")
                "Audio" -> audio += StreamLang(lang, title, forced, st.optBoolean("IsDefault"))
                "Subtitle" -> if (st.optBoolean("IsExternal")) {
                    val index = st.optInt("Index", -1).takeIf { it >= 0 } ?: continue
                    val format = when (val codec = st.optString("Codec").lowercase()) {
                        "ass", "ssa" -> codec
                        "vtt", "webvtt" -> "vtt"
                        else -> "srt"
                    }
                    external += ExternalSubtitle(
                        url = "${s.server}/Videos/$itemId/$sourceId/Subtitles/$index/Stream.$format?api_key=${s.token}",
                        title = title.ifBlank { lang ?: "Sous-titres $index" },
                        language = lang,
                        forced = forced,
                    )
                } else {
                    subtitles += StreamLang(lang, title, forced, st.optBoolean("IsDefault"))
                }
            }
        }
        val runtimeMs = (json.optLongOrNull("RunTimeTicks") ?: 0L) / MediaItem.TICKS_PER_MS
        val segments = runCatching { mediaSegments(itemId) }.getOrDefault(emptyList())
            .ifEmpty { chapterSegments(json.optJSONArray("Chapters"), runtimeMs) }
        return PlaybackInfo(audio, subtitles, external, segments, videoCodec)
    }

    private suspend fun mediaSegments(itemId: String): List<Segment> {
        val arr = JSONObject(call("GET", url("/MediaSegments/$itemId"))).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Segment(
                type = o.optString("Type"),
                startMs = o.optLong("StartTicks") / MediaItem.TICKS_PER_MS,
                endMs = o.optLong("EndTicks") / MediaItem.TICKS_PER_MS,
            )
        }
    }

    /** Chapitres nommés « Intro », « Opening », « Générique », « Credits »… */
    private fun chapterSegments(chapters: JSONArray?, runtimeMs: Long): List<Segment> {
        if (chapters == null || chapters.length() == 0) return emptyList()
        val list = (0 until chapters.length()).mapNotNull { chapters.optJSONObject(it) }
        return list.mapIndexedNotNull { i, c ->
            val name = c.optString("Name").lowercase()
            val start = c.optLong("StartPositionTicks") / MediaItem.TICKS_PER_MS
            val end = list.getOrNull(i + 1)?.optLong("StartPositionTicks")?.div(MediaItem.TICKS_PER_MS) ?: runtimeMs
            val type = when {
                Regex("credit|générique de fin|generique de fin|ending|outro|end credits").containsMatchIn(name) -> "Outro"
                Regex("^(intro|opening|ouverture|générique|generique|op)\\b|introduction").containsMatchIn(name) -> "Intro"
                Regex("recap|previously|précédemment|precedemment|résumé").containsMatchIn(name) -> "Recap"
                else -> null
            } ?: return@mapIndexedNotNull null
            if (end <= start) null else Segment(type, start, end)
        }
    }

    /** L'épisode qui suit [episodeId] dans la série, s'il existe. */
    suspend fun nextEpisode(seriesId: String, episodeId: String): MediaItem? {
        val items = parseItems(call("GET", url("/Shows/$seriesId/Episodes", baseParams() + mapOf("StartItemId" to episodeId, "Limit" to 2))))
        return if (items.firstOrNull()?.id == episodeId) items.getOrNull(1) else null
    }

    /** Codec vidéo de chaque film et épisode : (id, id de la série ou null, codec). */
    suspend fun videoCodecs(): List<Triple<String, String?, String?>> {
        val params = mapOf(
            "userId" to current.userId,
            "IncludeItemTypes" to "Movie,Episode",
            "Recursive" to true,
            "Fields" to "MediaStreams",
            "EnableImages" to false,
            "EnableUserData" to false,
            "Limit" to 20000,
        )
        val arr = JSONObject(call("GET", url("/Items", params))).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optStringOrNull("Id") ?: return@mapNotNull null
            val streams = o.optJSONArray("MediaStreams")
            var codec: String? = null
            if (streams != null) {
                for (k in 0 until streams.length()) {
                    val st = streams.optJSONObject(k) ?: continue
                    if (st.optString("Type") == "Video") {
                        codec = st.optStringOrNull("Codec")
                        break
                    }
                }
            }
            Triple(id, o.optStringOrNull("SeriesId"), codec)
        }
    }

    /** Codec de la vidéo (« h264 », « hevc », « av1 »…), pour prévenir si l'appareil risque de ramer. */
    suspend fun videoCodec(itemId: String): String? {
        val s = current
        val params = mapOf("userId" to s.userId, "Fields" to "MediaSources")
        val text = try {
            call("GET", url("/Items/$itemId", params))
        } catch (e: JellyfinException) {
            if (e.code == 404) call("GET", url("/Users/${s.userId}/Items/$itemId", mapOf("Fields" to "MediaSources"))) else throw e
        }
        val json = JSONObject(text)
        val streams = json.optJSONArray("MediaSources")?.optJSONObject(0)?.optJSONArray("MediaStreams")
            ?: json.optJSONArray("MediaStreams")
            ?: return null
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            if (stream.optString("Type") == "Video") return stream.optStringOrNull("Codec")
        }
        return null
    }

    // ---------------------------------------------------------------- Séries : saisons et épisodes

    suspend fun seasons(seriesId: String): List<SeasonInfo> {
        val text = call("GET", url("/Shows/$seriesId/Seasons", mapOf("userId" to current.userId, "EnableUserData" to true)))
        val arr = JSONObject(text).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optStringOrNull("Id") ?: return@mapNotNull null
            SeasonInfo(
                id = id,
                name = o.optString("Name").ifBlank { "Saison" },
                index = o.optIntOrNull("IndexNumber"),
                played = o.optJSONObject("UserData")?.optBoolean("Played") ?: false,
            )
        }
    }

    suspend fun episodes(seriesId: String, seasonId: String): List<MediaItem> =
        parseItems(call("GET", url("/Shows/$seriesId/Episodes", baseParams() + mapOf("SeasonId" to seasonId))))

    suspend fun reportStart(itemId: String, positionTicks: Long) =
        report("/Sessions/Playing", itemId, positionTicks, paused = false)

    suspend fun reportProgress(itemId: String, positionTicks: Long, paused: Boolean) =
        report("/Sessions/Playing/Progress", itemId, positionTicks, paused)

    suspend fun reportStopped(itemId: String, positionTicks: Long) =
        report("/Sessions/Playing/Stopped", itemId, positionTicks, paused = false)

    private suspend fun report(path: String, itemId: String, positionTicks: Long, paused: Boolean) {
        val s = session ?: return
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("MediaSourceId", itemId)
            .put("PositionTicks", positionTicks)
            .put("IsPaused", paused)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectPlay")
            .toString()
        runCatching { call("POST", s.server + path, body) }
    }

    // ---------------------------------------------------------------- HTTP

    /** En-tête de connexion Jellyfin, pour le petit service NosFlix du serveur (il vérifie le jeton). */
    internal fun authorization(token: String = current.token): String = authHeader(token)

    private fun authHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"NosFlix\", Device=\"")
        append(deviceName.replace("\"", ""))
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(VERSION).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    /**
     * Appel à Jellyfin. Si le serveur ne répond plus à son adresse (on a quitté la maison,
     * ou on y est revenu), on cherche par où le joindre et on refait l'appel une fois.
     */
    internal suspend fun call(
        method: String,
        url: String,
        body: String? = null,
        token: String? = session?.token,
        retry: Boolean = true,
    ): String = try {
        send(method, url, body, token)
    } catch (e: IOException) {
        if (!retry) throw e
        val base = ServerRoute.baseOf(url, session?.server) ?: throw e
        val moved = reroute(failed = base)?.takeIf { it != base } ?: throw e
        send(method, moved + url.removePrefix(base), body, token)
    }

    // ---------------------------------------------------------------- Par où joindre le serveur

    /** Identifiant du serveur Jellyfin (« ServerId ») : on ne se branche que sur celui-là. */
    @Volatile
    var serverId: String? = null

    /** Appelé chaque fois que le serveur a été trouvé, à son adresse habituelle ou à une nouvelle. */
    @Volatile
    var onRoute: ((server: String, serverId: String) -> Unit)? = null

    private val routeLock = Mutex()
    private var routedAt = 0L

    private val probeHttp by lazy {
        http.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Cherche l'adresse où le serveur répond : le réseau de la maison d'abord, sinon Tailscale.
     * La session prend cette adresse.
     *
     * [failed] : l'adresse qui vient d'échouer. Si un autre appel a déjà changé d'adresse, on donne la nouvelle ;
     * sinon on ne cherche pas plus d'une fois toutes les 4 s.
     * [minGapMs] : ne pas chercher si on l'a fait il y a moins que ça.
     * Renvoie l'adresse trouvée, ou null si rien ne répond (ou si on a cherché trop récemment).
     */
    suspend fun reroute(failed: String? = null, minGapMs: Long = 0): String? = routeLock.withLock {
        val current = session?.server ?: failed ?: return@withLock null
        if (failed != null && current != failed) return@withLock current
        val now = SystemClock.elapsedRealtime()
        val gap = if (failed != null) maxOf(minGapMs, 4_000L) else minGapMs
        if (routedAt != 0L && now - routedAt < gap) return@withLock null
        routedAt = now
        val (found, id) = findServer(ServerRoute.candidates(current)) ?: return@withLock null
        serverId = id
        session?.let { s -> if (s.server != found) session = s.copy(server = found) }
        onRoute?.invoke(found, id)
        found
    }

    /** Essaie toutes les adresses en même temps et garde la première de la liste qui répond. */
    private suspend fun findServer(candidates: List<String>): Pair<String, String>? = coroutineScope {
        val probes = candidates.map { address -> async { probe(address) } }
        var found: Pair<String, String>? = null
        for ((i, probe) in probes.withIndex()) {
            val id = probe.await() ?: continue
            val known = serverId
            if (known == null || id == known) {
                found = candidates[i] to id
                break
            }
        }
        probes.forEach { it.cancel() }
        found
    }

    /** L'identifiant du Jellyfin qui répond à [server], ou null s'il ne répond pas. */
    private suspend fun probe(server: String): String? = suspendCancellableCoroutine { cont ->
        val request = runCatching { Request.Builder().url("$server/System/Info/Public").build() }.getOrNull()
        if (request == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val call = probeHttp.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val id = response.use { r ->
                    if (r.isSuccessful) runCatching { JSONObject(r.body.string()).optStringOrNull("Id") }.getOrNull() else null
                }
                if (cont.isActive) cont.resume(id)
            }
        })
    }

    private suspend fun send(
        method: String,
        url: String,
        body: String?,
        token: String?,
    ): String = withContext(Dispatchers.IO) {
        val requestBody = when {
            body != null -> body.toRequestBody(JSON)
            method == "POST" -> "".toRequestBody(JSON)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .method(method, requestBody)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw JellyfinException(messageFor(response.code), response.code)
            text
        }
    }

    internal fun url(path: String, params: Map<String, Any?> = emptyMap()): String {
        val builder = (current.server + path).toHttpUrl().newBuilder()
        for ((key, value) in params) if (value != null) builder.addQueryParameter(key, value.toString())
        return builder.build().toString()
    }

    private fun baseParams(): Map<String, Any?> = mapOf(
        "userId" to current.userId,
        "Fields" to "Overview,Genres,DateCreated,ChildCount,ProviderIds",
        "EnableImageTypes" to "Primary,Backdrop,Logo,Thumb",
        "ImageTypeLimit" to 1,
        "EnableUserData" to true,
    )

    // ---------------------------------------------------------------- Lecture du JSON

    private fun parseItems(text: String, unfiltered: Boolean = false): List<MediaItem> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("Items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseItem) }
        return if (unfiltered) items else items.filter(::visibleForProfile)
    }

    /** Profil enfant : films et séries choisis par les parents, et les épisodes de ces séries. */
    private fun visibleForProfile(item: MediaItem): Boolean {
        val allowed = kidAllowed ?: return true
        return when (item.type) {
            "Movie", "Series", "Video", "BoxSet", "MusicVideo" -> item.id in allowed
            "Episode", "Season" -> item.seriesId in allowed
            else -> true
        }
    }

    private fun parseItem(o: JSONObject): MediaItem? {
        val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return null
        val type = o.optString("Type")
        val userData = o.optJSONObject("UserData")
        val imageTags = o.optJSONObject("ImageTags")
        val backdrops = o.optJSONArray("BackdropImageTags")
        val parentBackdrops = o.optJSONArray("ParentBackdropImageTags")
        val seriesId = o.optStringOrNull("SeriesId")

        val backdropId = when {
            backdrops != null && backdrops.length() > 0 -> id
            parentBackdrops != null && parentBackdrops.length() > 0 -> o.optStringOrNull("ParentBackdropItemId")
            else -> null
        }
        val logoId = when {
            imageTags?.has("Logo") == true -> id
            else -> o.optStringOrNull("ParentLogoItemId")
        }
        val posterId = if (type == "Episode" && seriesId != null && o.has("SeriesPrimaryImageTag")) seriesId else id

        return MediaItem(
            id = id,
            name = o.optString("Name"),
            type = type,
            overview = o.optString("Overview").trim(),
            year = o.optIntOrNull("ProductionYear"),
            runTimeTicks = o.optLongOrNull("RunTimeTicks"),
            rating = o.optDoubleOrNull("CommunityRating"),
            genres = o.optJSONArray("Genres").toStrings(),
            dateCreatedMs = o.optStringOrNull("DateCreated")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            seriesId = seriesId,
            seriesName = o.optStringOrNull("SeriesName"),
            season = o.optIntOrNull("ParentIndexNumber"),
            episode = o.optIntOrNull("IndexNumber"),
            childCount = o.optIntOrNull("ChildCount"),
            playedPercentage = userData?.optDoubleOrNull("PlayedPercentage"),
            positionTicks = userData?.optLong("PlaybackPositionTicks") ?: 0L,
            played = userData?.optBoolean("Played") ?: false,
            favorite = userData?.optBoolean("IsFavorite") ?: false,
            posterItemId = posterId,
            backdropItemId = backdropId,
            logoItemId = logoId,
            tmdbId = o.optJSONObject("ProviderIds")?.optStringOrNull("Tmdb")?.toIntOrNull(),
            officialRating = o.optStringOrNull("OfficialRating"),
        )
    }

    private fun messageFor(code: Int): String = when (code) {
        400 -> "Le serveur a refusé la demande."
        401 -> "Nom d’utilisateur ou mot de passe incorrect (ou session expirée)."
        403 -> "Accès refusé par le serveur."
        404 -> "Élément introuvable sur le serveur."
        in 500..599 -> "Le serveur Jellyfin a rencontré une erreur ($code)."
        else -> "Erreur du serveur ($code)."
    }

    companion object {
        const val VERSION = "0.1.0"
        private val JSON = "application/json".toMediaType()

        /** Accepte « 192.168.1.1 », « 192.168.1.1:8096 » ou une adresse complète. */
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
            if (Regex("^https?://[^/:]+$", RegexOption.IGNORE_CASE).matches(s)) s += ":8096"
            return s
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

internal fun JSONArray?.toStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
