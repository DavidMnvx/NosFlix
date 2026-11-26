package fr.nosflix.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Appels Jellyfin pour la musique et les livres audio. */
class AudioApi(private val client: JellyfinClient) {

    private data class Libraries(val key: String, val books: List<String>)

    @Volatile
    private var libraries: Libraries? = null

    /** Bibliothèques « Livres » de l'utilisateur (la musique, elle, se cherche dans tout le serveur). */
    private suspend fun bookLibraries(): List<String> {
        val s = client.current
        val key = s.server + "|" + s.userId
        libraries?.takeIf { it.key == key }?.let { return it.books }
        val arr = JSONObject(client.call("GET", client.url("/UserViews", mapOf("userId" to s.userId)))).optJSONArray("Items") ?: JSONArray()
        val books = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("CollectionType") == "books") o.optStringOrNull("Id") else null
        }
        libraries = Libraries(key, books)
        return books
    }

    private fun base(): Map<String, Any?> = mapOf(
        "userId" to client.current.userId,
        "EnableImageTypes" to "Primary",
        "ImageTypeLimit" to 1,
        "EnableUserData" to true,
    )

    // ---------------------------------------------------------------- Musique

    suspend fun albums(
        sortBy: String = "SortName",
        order: String = "Ascending",
        limit: Int = 60,
        search: String? = null,
        artistIds: List<String>? = null,
    ): List<AudioAlbum> {
        val params = base() + mapOf(
            "IncludeItemTypes" to "MusicAlbum",
            "Recursive" to true,
            "SortBy" to sortBy,
            "SortOrder" to order,
            "Limit" to limit,
            "SearchTerm" to search,
            "ArtistIds" to artistIds?.joinToString("|"),
            "Fields" to "ChildCount,Genres",
        )
        return items(client.call("GET", client.url("/Items", params))).mapNotNull(::parseAlbum)
    }

    /** Identifiants des artistes dont le nom contient [term] (« saez » → Saez). */
    suspend fun searchArtists(term: String, limit: Int = 8): List<String> {
        val params = mapOf(
            "userId" to client.current.userId,
            "searchTerm" to term,
            "Limit" to limit,
            "enableImages" to false,
            "enableTotalRecordCount" to false,
        )
        return items(client.call("GET", client.url("/Artists", params))).mapNotNull { it.optStringOrNull("Id") }
    }

    suspend fun tracks(
        sortBy: String = "SortName",
        order: String = "Ascending",
        limit: Int = 100,
        filters: String? = null,
        search: String? = null,
        parentId: String? = null,
        albumIds: List<String>? = null,
        artistIds: List<String>? = null,
    ): List<AudioTrack> {
        val params = base() + mapOf(
            "IncludeItemTypes" to "Audio",
            "Recursive" to true,
            "SortBy" to sortBy,
            "SortOrder" to order,
            "Limit" to limit,
            "Filters" to filters,
            "SearchTerm" to search,
            "ParentId" to parentId,
            "AlbumIds" to albumIds?.joinToString("|"),
            "ArtistIds" to artistIds?.joinToString("|"),
            "Fields" to "Path,DateCreated",
        )
        return items(client.call("GET", client.url("/Items", params))).mapNotNull(::parseTrack)
    }

    suspend fun albumTracks(albumId: String): List<AudioTrack> =
        tracks(sortBy = "ParentIndexNumber,IndexNumber,SortName", limit = 1000, parentId = albumId)
            .sortedWith(compareBy<AudioTrack>({ it.disc ?: 0 }, { it.index ?: Int.MAX_VALUE }).thenComparator { a, b -> NaturalOrder.compare(a.path ?: a.title, b.path ?: b.title) })

    suspend fun trackCount(): Int {
        val params = base() + mapOf("IncludeItemTypes" to "Audio", "Recursive" to true, "Limit" to 0)
        return JSONObject(client.call("GET", client.url("/Items", params))).optInt("TotalRecordCount")
    }

    suspend fun playlists(): List<AudioPlaylist> {
        val params = base() + mapOf(
            "IncludeItemTypes" to "Playlist",
            "Recursive" to true,
            "SortBy" to "SortName",
            "Fields" to "ChildCount",
        )
        return items(client.call("GET", client.url("/Items", params))).mapNotNull { o ->
            val media = o.optString("MediaType")
            if (media.isNotBlank() && media != "Audio" && media != "Unknown") return@mapNotNull null
            val id = o.optStringOrNull("Id") ?: return@mapNotNull null
            AudioPlaylist(
                id = id,
                name = o.optString("Name").ifBlank { "Playlist" },
                trackCount = o.optIntOrNull("ChildCount"),
                imageItemId = if (o.optJSONObject("ImageTags")?.has("Primary") == true) id else null,
            )
        }
    }

    suspend fun playlistTracks(playlistId: String): List<AudioTrack> {
        val params = base() + mapOf("Fields" to "Path,DateCreated")
        return items(client.call("GET", client.url("/Playlists/$playlistId/Items", params))).mapNotNull(::parseTrack)
    }

    /** Crée une playlist (éventuellement vide) et renvoie son identifiant. */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val body = JSONObject()
            .put("Name", name)
            .put("Ids", JSONArray(trackIds))
            .put("UserId", client.current.userId)
            .put("MediaType", "Audio")
            .toString()
        val json = JSONObject(client.call("POST", client.url("/Playlists"), body))
        return json.optString("Id")
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        client.call("POST", client.url("/Playlists/$playlistId/Items", mapOf("ids" to trackIds.joinToString(","), "userId" to client.current.userId)))
    }

    suspend fun removeFromPlaylist(playlistId: String, entryIds: List<String>) {
        if (entryIds.isEmpty()) return
        client.call("DELETE", client.url("/Playlists/$playlistId/Items", mapOf("entryIds" to entryIds.joinToString(","))))
    }

    // ---------------------------------------------------------------- Livres audio

    /** Tous les livres audio, avec leurs fichiers et où tu en es dans chacun. */
    suspend fun books(): List<AudioBook> {
        val result = mutableListOf<AudioBook>()
        for (library in bookLibraries()) {
            val topParams = base() + mapOf(
                "ParentId" to library,
                "Fields" to "Path,ChildCount,DateCreated",
                "SortBy" to "SortName",
            )
            val top = items(client.call("GET", client.url("/Items", topParams)))
            val partParams = base() + mapOf(
                "ParentId" to library,
                "Recursive" to true,
                "IncludeItemTypes" to "AudioBook",
                "Fields" to "Path,Chapters,DateCreated",
                "Limit" to 20000,
            )
            val parts = items(client.call("GET", client.url("/Items", partParams))).mapNotNull(::parseTrack)

            for (o in top) {
                val id = o.optStringOrNull("Id") ?: continue
                val type = o.optString("Type")
                val name = o.optString("Name")
                val folderImage = if (o.optJSONObject("ImageTags")?.has("Primary") == true) id else null
                val created = o.optStringOrNull("DateCreated")?.let(::parseDate)
                val bookParts = when (type) {
                    "AudioBook" -> parts.filter { it.id == id }.ifEmpty { listOfNotNull(parseTrack(o)) }
                    "Folder" -> {
                        val path = o.optStringOrNull("Path")?.trimEnd('/', '\\') ?: continue
                        parts.filter { p -> p.path != null && (p.path.startsWith("$path/") || p.path.startsWith("$path\\")) }
                            .sortedWith { a, b -> NaturalOrder.compare(a.path.orEmpty(), b.path.orEmpty()) }
                    }
                    else -> emptyList()
                }
                if (bookParts.isEmpty()) continue
                result += makeBook(id, name, type == "Folder", folderImage, bookParts, created)
            }
        }
        return result
    }

    private fun makeBook(id: String, name: String, folder: Boolean, folderImage: String?, parts: List<AudioTrack>, created: Long?): AudioBook {
        val first = parts.first()
        val (parsedAuthor, parsedTitle) = ReleaseName.split(name)
        // Le titre de l'album (balises du fichier) s'il est commun aux fichiers, sinon le nom du dossier nettoyé.
        val album = first.album.trim().takeIf { a -> a.isNotBlank() && parts.count { it.album.trim() == a } * 2 >= parts.size }
        val title = when {
            album != null -> album
            !folder -> first.title.ifBlank { parsedTitle }
            else -> parsedTitle
        }
        val author = first.artist.trim().ifBlank { null } ?: parsedAuthor
        val image = folderImage ?: parts.firstOrNull { it.imageItemId != null }?.imageItemId
        return AudioBook(
            id = id,
            title = unUnderscore(title),
            author = author?.let(::unUnderscore),
            imageItemId = image,
            parts = parts,
            dateCreatedMs = created ?: first.dateCreatedMs,
        )
    }

    /** « Je_t_ai_donne_mon_coeur » → « Je t ai donne mon coeur ». */
    private fun unUnderscore(text: String): String =
        if (text.contains('_') && !text.contains(' ')) text.replace('_', ' ').trim() else text

    // ---------------------------------------------------------------- Lecture

    /**
     * Fichier d'origine si le téléphone sait le lire (mp3, flac, m4a…), sinon conversion en mp3 par le serveur.
     */
    fun streamUrl(track: AudioTrack, forceTranscode: Boolean = false): String {
        val s = client.current
        val direct = !forceTranscode && (track.extension == null || track.extension in DirectFormats)
        return if (direct) {
            "${s.server}/Audio/${track.id}/stream?static=true&api_key=${s.token}"
        } else {
            "${s.server}/Audio/${track.id}/stream.mp3?audioCodec=mp3&audioBitRate=320000&api_key=${s.token}"
        }
    }

    fun imageUrl(itemId: String?, size: Int): String? = client.imageUrl(itemId, "Primary", fillWidth = size, fillHeight = size)

    // ---------------------------------------------------------------- JSON

    private fun items(text: String): List<JSONObject> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("Items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun parseAlbum(o: JSONObject): AudioAlbum? {
        val id = o.optStringOrNull("Id") ?: return null
        val name = o.optString("Name")
        val tagArtist = o.optStringOrNull("AlbumArtist") ?: o.optJSONArray("Artists").toStrings().firstOrNull()
        // Pas encore de balises (analyse du serveur en cours) : on lit « Artiste - Album (année) [FLAC] ».
        val (folderArtist, folderTitle) = ReleaseName.split(name)
        return AudioAlbum(
            id = id,
            title = if (tagArtist == null) folderTitle else name,
            artist = tagArtist ?: folderArtist.orEmpty(),
            year = o.optIntOrNull("ProductionYear") ?: Regex("\\((19|20)\\d{2}\\)").find(name)?.value?.trim('(', ')')?.toIntOrNull(),
            imageItemId = if (o.optJSONObject("ImageTags")?.has("Primary") == true) id else null,
            trackCount = o.optIntOrNull("ChildCount"),
            styles = MusicStyle.of(o.optJSONArray("Genres").toStrings()),
        )
    }

    private fun parseTrack(o: JSONObject): AudioTrack? {
        val id = o.optStringOrNull("Id") ?: return null
        val userData = o.optJSONObject("UserData")
        val path = o.optStringOrNull("Path")
        val albumId = o.optStringOrNull("AlbumId")
        val image = when {
            o.optJSONObject("ImageTags")?.has("Primary") == true -> id
            o.optStringOrNull("AlbumPrimaryImageTag") != null -> albumId
            else -> null
        }
        val fileName = path?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')
        val chapters = o.optJSONArray("Chapters")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                AudioChapter(
                    title = c.optString("Name").ifBlank { "Chapitre ${i + 1}" },
                    startMs = c.optLong("StartPositionTicks") / MediaItem.TICKS_PER_MS,
                )
            }
        }.orEmpty()
        return AudioTrack(
            id = id,
            title = o.optString("Name").ifBlank { fileName.orEmpty() },
            artist = o.optStringOrNull("AlbumArtist") ?: o.optJSONArray("Artists").toStrings().joinToString(", "),
            album = o.optString("Album"),
            albumId = albumId,
            durationMs = (o.optLongOrNull("RunTimeTicks") ?: 0L) / MediaItem.TICKS_PER_MS,
            index = o.optIntOrNull("IndexNumber"),
            disc = o.optIntOrNull("ParentIndexNumber"),
            imageItemId = image,
            path = path,
            positionMs = (userData?.optLong("PlaybackPositionTicks") ?: 0L) / MediaItem.TICKS_PER_MS,
            played = userData?.optBoolean("Played") ?: false,
            favorite = userData?.optBoolean("IsFavorite") ?: false,
            lastPlayedMs = userData?.optStringOrNull("LastPlayedDate")?.let(::parseDate),
            dateCreatedMs = o.optStringOrNull("DateCreated")?.let(::parseDate),
            playlistEntryId = o.optStringOrNull("PlaylistItemId"),
            chapters = chapters,
        )
    }

    private fun parseDate(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    private companion object {
        /** Formats lus directement par le lecteur audio d'Android. */
        val DirectFormats = setOf("mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "mp4")
    }
}
