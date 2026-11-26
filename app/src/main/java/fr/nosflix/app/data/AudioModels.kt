package fr.nosflix.app.data

/** Un morceau de musique, ou une partie (un fichier) d'un livre audio. */
data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String?,
    val durationMs: Long,
    val index: Int?,
    val disc: Int?,
    /** Élément Jellyfin dont on affiche l'image « Primary » (le morceau ou son album). */
    val imageItemId: String?,
    val path: String?,
    val positionMs: Long,
    val played: Boolean,
    val favorite: Boolean,
    val lastPlayedMs: Long?,
    val dateCreatedMs: Long?,
    /** Dans une playlist : identifiant de l'entrée (pour la retirer). */
    val playlistEntryId: String? = null,
    /** Chapitres intégrés au fichier (livres audio .m4b). */
    val chapters: List<AudioChapter> = emptyList(),
) {
    /** Extension du fichier (« flac », « mp3 »…), pour savoir si le téléphone sait le lire tel quel. */
    val extension: String? get() = path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 5 }
}

data class AudioChapter(val title: String, val startMs: Long)

data class AudioAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val imageItemId: String?,
    val trackCount: Int?,
    /** Familles de styles (pastilles de la page Musique), d'après les genres des fichiers. */
    val styles: Set<MusicStyle> = emptySet(),
)

data class AudioPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val imageItemId: String?,
)

/** Un livre audio : un fichier seul (.m4b, .mp3) ou un dossier de plusieurs fichiers. */
data class AudioBook(
    val id: String,
    val title: String,
    val author: String?,
    val imageItemId: String?,
    val parts: List<AudioTrack>,
    val dateCreatedMs: Long?,
) {
    val totalMs: Long = parts.sumOf { it.durationMs }

    val listenedMs: Long = parts.sumOf { if (it.played) it.durationMs else it.positionMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }

    val lastPlayedMs: Long? = parts.mapNotNull { it.lastPlayedMs }.maxOrNull()

    val finished: Boolean get() = parts.isNotEmpty() && parts.all { it.played }

    val started: Boolean get() = lastPlayedMs != null || listenedMs > 0

    val inProgress: Boolean get() = started && !finished

    val progress: Float get() = if (totalMs > 0) (listenedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    val remainingMs: Long get() = (totalMs - listenedMs).coerceAtLeast(0)

    /** Chapitres affichés : les fichiers s'il y en a plusieurs, sinon les chapitres du fichier unique. */
    val chapterCount: Int get() = if (parts.size > 1) parts.size else parts.firstOrNull()?.chapters?.size ?: 0

    /** Où reprendre : (n° de partie, position en ms). */
    fun resumePoint(): Pair<Int, Long> {
        if (parts.isEmpty()) return 0 to 0L
        val last = parts.indices.filter { parts[it].lastPlayedMs != null }.maxByOrNull { parts[it].lastPlayedMs ?: 0L }
            ?: parts.indexOfFirst { it.positionMs > 0 && !it.played }.takeIf { it >= 0 }
            ?: return (parts.indexOfFirst { !it.played }.takeIf { it >= 0 } ?: 0) to 0L
        val part = parts[last]
        val nearEnd = part.durationMs > 0 && part.positionMs >= part.durationMs - 5_000
        if (!part.played && !nearEnd) return last to part.positionMs
        val next = (last + 1 until parts.size).firstOrNull { !parts[it].played }
        return if (next != null) next to parts[next].positionMs else last to 0L
    }
}

// ---------------------------------------------------------------- États des écrans

sealed interface MusicHomeState {
    data object Loading : MusicHomeState
    data class Error(val message: String) : MusicHomeState
    data class Ready(
        val recent: List<AudioAlbum>,
        val playlists: List<AudioPlaylist>,
        val added: List<AudioAlbum>,
        val discover: List<AudioAlbum>,
        val all: List<AudioAlbum>,
        val trackCount: Int,
        /** Styles présents et leur nombre d'albums, pour les pastilles. */
        val styles: List<Pair<MusicStyle, Int>> = emptyList(),
    ) : MusicHomeState
}

enum class CollectionKind { Album, Playlist, Liked }

/** Un album, une playlist ou les titres likés, ouverts à l'écran. */
data class CollectionState(
    val kind: CollectionKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageItemId: String?,
    val loading: Boolean = true,
    val tracks: List<AudioTrack> = emptyList(),
    val error: String? = null,
)

data class MusicSearch(
    val term: String,
    val loading: Boolean,
    val albums: List<AudioAlbum> = emptyList(),
    val tracks: List<AudioTrack> = emptyList(),
    val playlists: List<AudioPlaylist> = emptyList(),
)

sealed interface BooksState {
    data object Loading : BooksState
    data class Error(val message: String) : BooksState
    data class Ready(val books: List<AudioBook>) : BooksState
}

enum class BookSort(val label: String) {
    Recent("Ajoutés récemment"),
    Az("A → Z"),
    Author("Auteur"),
}

// ---------------------------------------------------------------- Noms de dossiers

/**
 * Nettoie un nom de dossier ou de fichier téléchargé :
 * « Devenir.Super.Conscient.Joe.Dispenza.FR.[MP3.128.kbps]-Alex » → « Devenir Super Conscient Joe Dispenza ».
 */
object ReleaseName {
    private val Tags = Regex(
        "(?i)\\b(french|fr|vf|mp3|flac|aac|m4b|kbps|\\d{2,3} ?kbps|cbr|vbr|cd-?rip|\\d{2}bit|\\d{2}b-\\d+(\\.\\d+)?khz|\\d+(\\.\\d+)? ?khz|livre audio|audiobook)\\b",
    )

    fun clean(raw: String): String {
        var s = raw.trim()
        val dotted = !s.contains(' ') && s.count { it == '.' } >= 2
        s = s.replace(Regex("\\[[^]]*]"), " ")
        s = s.replace(Regex("\\{[^}]*\\}"), " ")
        if (dotted || raw.contains('[')) s = s.replace(Regex("\\s*-[A-Za-z0-9]+\\s*$"), "")
        if (dotted) s = s.replace('.', ' ').replace('_', ' ')
        s = s.replace(Regex("\\((19|20)\\d{2}\\)"), " ")
        s = s.replace(Tags, " ")
        s = s.replace(Regex("\\(\\s*\\)"), " ")
        s = s.replace(Regex("\\s{2,}"), " ")
        return s.trim(' ', '-', '_', '.', ',').ifBlank { raw.trim() }
    }

    /** (auteur ou artiste, titre) d'après « Auteur - Titre ». */
    fun split(raw: String): Pair<String?, String> {
        val c = clean(raw)
        val parts = c.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[0] to parts.drop(1).joinToString(" - ") else null to c
    }
}

/** Tri « naturel » : « 2 - Chapitre » avant « 10 - Chapitre ». */
object NaturalOrder : Comparator<String> {
    private val Chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = Chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = Chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ca.size - cb.size
    }
}
