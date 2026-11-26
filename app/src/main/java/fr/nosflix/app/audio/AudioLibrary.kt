package fr.nosflix.app.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.nosflix.app.data.AudioAlbum
import fr.nosflix.app.data.AudioApi
import fr.nosflix.app.data.AudioBook
import fr.nosflix.app.data.AudioPlaylist
import fr.nosflix.app.data.AudioTrack
import fr.nosflix.app.data.BookSort
import fr.nosflix.app.data.BooksState
import fr.nosflix.app.data.CollectionKind
import fr.nosflix.app.data.CollectionState
import fr.nosflix.app.data.MusicHomeState
import fr.nosflix.app.data.MusicSearch
import fr.nosflix.app.data.MusicStyle
import fr.nosflix.app.data.plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Ce que les écrans Musique et Livres audio affichent, et ce qu'ils chargent. */
class AudioLibrary(
    private val scope: CoroutineScope,
    private val api: AudioApi,
    private val friendly: (Throwable) -> String,
) {
    // ------------------------------------------------------------ Musique
    var music by mutableStateOf<MusicHomeState>(MusicHomeState.Loading)
        private set
    var playlists by mutableStateOf<List<AudioPlaylist>>(emptyList())
        private set
    var collection by mutableStateOf<CollectionState?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var search by mutableStateOf<MusicSearch?>(null)
        private set

    /** Pastille de style choisie sous la recherche (null = tous les styles). */
    var style by mutableStateOf<MusicStyle?>(null)
        private set

    fun pickStyle(next: MusicStyle?) {
        if (next == style) return
        style = next
        musicScroll = 0 to 0
    }

    /** Albums du style choisi (tous si aucun). */
    fun albumsOf(all: List<AudioAlbum>): List<AudioAlbum> {
        val s = style ?: return all
        return all.filter { s in it.styles }
    }

    // ------------------------------------------------------------ Livres audio
    var books by mutableStateOf<BooksState>(BooksState.Loading)
        private set
    var bookSort by mutableStateOf(BookSort.Recent)
    var bookQuery by mutableStateOf("")
    var bookId by mutableStateOf<String?>(null)
        private set

    /** Petit message passager (« Ajouté à “Été 2026” »). */
    var message by mutableStateOf<String?>(null)
        private set

    /** Fenêtre « Ajouter à une playlist » ouverte pour ces morceaux (liste vide = nouvelle playlist). */
    var addingTracks by mutableStateOf<List<AudioTrack>?>(null)

    fun askAddToPlaylist(tracks: List<AudioTrack>) {
        ensurePlaylists()
        addingTracks = tracks
    }

    /**
     * « Lecture aléatoire » : 200 morceaux pris au hasard dans toute la musique,
     * ou seulement dans le style choisi (les morceaux de 40 de ses albums tirés au sort).
     */
    fun shuffleAll(play: (List<AudioTrack>, String) -> Unit) {
        val s = style
        val albums = (music as? MusicHomeState.Ready)?.all?.let(::albumsOf)
        scope.launch {
            try {
                val tracks = if (s != null && albums != null) {
                    if (albums.isEmpty()) emptyList() else api.tracks(sortBy = "Random", limit = 200, albumIds = albums.shuffled().take(40).map { it.id })
                } else {
                    api.tracks(sortBy = "Random", limit = 200)
                }
                val label = if (s != null) s.label else "Toute ta musique"
                if (tracks.isEmpty()) toast("Aucun morceau dans ta musique pour l’instant.") else play(tracks, label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(friendly(e))
            }
        }
    }

    /** Position de la liste et dernière vignette ouverte, pour revenir au même endroit. */
    var musicScroll: Pair<Int, Int> = 0 to 0
    var musicFocusKey: String? = null
    var booksScroll: Pair<Int, Int> = 0 to 0
    var booksFocusKey: String? = null

    val book: AudioBook? get() = (books as? BooksState.Ready)?.books?.firstOrNull { it.id == bookId }

    private var musicJob: Job? = null
    private var collectionJob: Job? = null
    private var searchJob: Job? = null
    private var booksJob: Job? = null
    private var messageJob: Job? = null

    // ------------------------------------------------------------ Musique : accueil

    fun loadMusic(quiet: Boolean = false) {
        val keep = quiet && music is MusicHomeState.Ready
        if (!keep) music = MusicHomeState.Loading
        musicJob?.cancel()
        musicJob = scope.launch {
            try {
                music = buildMusic()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!keep) music = MusicHomeState.Error(friendly(e))
            }
        }
    }

    private suspend fun buildMusic(): MusicHomeState.Ready = coroutineScope {
        val allD = async { api.albums(limit = 5000) }
        val addedD = async { safe(emptyList()) { api.albums(sortBy = "DateCreated", order = "Descending", limit = 20) } }
        val randomD = async { safe(emptyList()) { api.albums(sortBy = "Random", limit = 20) } }
        val playedD = async { safe(emptyList()) { api.tracks(sortBy = "DatePlayed", order = "Descending", filters = "IsPlayed", limit = 80) } }
        val playlistsD = async { safe(emptyList()) { api.playlists() } }
        val countD = async { safe(0) { api.trackCount() } }

        val all = allD.await()
        val byId = all.associateBy { it.id }
        // « Écoutés récemment » : les albums des derniers morceaux écoutés.
        val recent = playedD.await()
            .mapNotNull { t -> t.albumId?.let { id -> byId[id] ?: AudioAlbum(id, t.album, t.artist, null, t.imageItemId, null) } }
            .distinctBy { it.id }
            .take(12)
        val lists = playlistsD.await()
        playlists = lists
        MusicHomeState.Ready(
            recent = recent,
            playlists = lists,
            added = addedD.await(),
            discover = randomD.await(),
            all = all,
            trackCount = countD.await(),
            styles = MusicStyle.count(all),
        )
    }

    // ------------------------------------------------------------ Musique : album, playlist, titres likés

    fun openAlbum(album: AudioAlbum) {
        val sub = listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" · ")
        openCollection(CollectionState(CollectionKind.Album, album.id, album.title, sub, album.imageItemId)) { api.albumTracks(album.id) }
    }

    fun openPlaylist(playlist: AudioPlaylist) {
        openCollection(CollectionState(CollectionKind.Playlist, playlist.id, playlist.name, "Playlist", playlist.imageItemId)) {
            api.playlistTracks(playlist.id)
        }
    }

    fun openLiked() {
        openCollection(CollectionState(CollectionKind.Liked, "liked", "Titres likés", "Tes coups de cœur", null)) {
            api.tracks(sortBy = "DateCreated", order = "Descending", filters = "IsFavorite", limit = 2000)
        }
    }

    /** Ouvre l'album d'un morceau (depuis la recherche ou un menu). */
    fun openAlbumOf(track: AudioTrack) {
        val id = track.albumId ?: return
        val known = (music as? MusicHomeState.Ready)?.all?.firstOrNull { it.id == id }
        openAlbum(known ?: AudioAlbum(id, track.album.ifBlank { "Album" }, track.artist, null, track.imageItemId, null))
    }

    fun reloadCollection() {
        val c = collection ?: return
        when (c.kind) {
            CollectionKind.Album -> openCollection(c.copy(loading = true, error = null)) { api.albumTracks(c.id) }
            CollectionKind.Playlist -> openCollection(c.copy(loading = true, error = null)) { api.playlistTracks(c.id) }
            CollectionKind.Liked -> openLiked()
        }
    }

    private fun openCollection(initial: CollectionState, load: suspend () -> List<AudioTrack>) {
        collection = initial
        collectionJob?.cancel()
        collectionJob = scope.launch {
            try {
                val tracks = load()
                if (collection?.id == initial.id) {
                    val image = initial.imageItemId ?: tracks.firstOrNull { it.imageItemId != null }?.imageItemId
                    collection = initial.copy(loading = false, tracks = tracks, imageItemId = image, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (collection?.id == initial.id) collection = initial.copy(loading = false, error = friendly(e))
            }
        }
    }

    // ------------------------------------------------------------ Musique : recherche

    fun onQuery(text: String) {
        query = text
        searchJob?.cancel()
        val term = text.trim()
        if (term.isEmpty()) {
            search = null
            return
        }
        search = MusicSearch(term, loading = true, albums = search?.albums.orEmpty(), tracks = search?.tracks.orEmpty())
        searchJob = scope.launch {
            delay(350)
            val result = coroutineScope {
                // Par titre (album ou morceau)…
                val albumsD = async { safe(emptyList()) { api.albums(search = term, limit = 30) } }
                val tracksD = async { safe(emptyList()) { api.tracks(search = term, limit = 50) } }
                // … et par artiste : ses albums, puis ses morceaux.
                val artistIds = safe(emptyList()) { api.searchArtists(term) }
                val artistAlbumsD = async {
                    if (artistIds.isEmpty()) emptyList() else safe(emptyList()) { api.albums(artistIds = artistIds, limit = 60) }
                }
                val artistTracksD = async {
                    if (artistIds.isEmpty()) emptyList() else safe(emptyList()) {
                        api.tracks(artistIds = artistIds, sortBy = "Album,ParentIndexNumber,IndexNumber", limit = 60)
                    }
                }
                // Albums sans balises : l'artiste vient du nom du dossier, Jellyfin ne le connaît pas.
                val key = searchKey(term)
                val byFolder = (music as? MusicHomeState.Ready)?.all.orEmpty().filter { searchKey(it.artist).contains(key) }.take(60)
                val albums = (albumsD.await() + artistAlbumsD.await() + byFolder).distinctBy { it.id }
                val tracks = (tracksD.await() + artistTracksD.await()).distinctBy { it.id }.take(100)
                val lists = playlists.filter { it.name.contains(term, ignoreCase = true) }
                MusicSearch(term, loading = false, albums = albums, tracks = tracks, playlists = lists)
            }
            search = result
        }
    }

    /** « Électro » → « electro » : pour comparer sans tenir compte des majuscules ni des accents. */
    private fun searchKey(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Accents, "")

    // ------------------------------------------------------------ Playlists

    fun addToPlaylist(playlist: AudioPlaylist, tracks: List<AudioTrack>) {
        scope.launch {
            try {
                api.addToPlaylist(playlist.id, tracks.map { it.id })
                toast(if (tracks.size == 1) "Ajouté à « ${playlist.name} »" else plural(tracks.size, "titre ajouté", "titres ajoutés") + " à « ${playlist.name} »")
                refreshPlaylists()
                if (collection?.id == playlist.id) reloadCollection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(friendly(e))
            }
        }
    }

    fun createPlaylist(name: String, tracks: List<AudioTrack>) {
        val clean = name.trim().ifBlank { return }
        scope.launch {
            try {
                api.createPlaylist(clean, tracks.map { it.id })
                toast("Playlist « $clean » créée")
                refreshPlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(friendly(e))
            }
        }
    }

    fun removeFromPlaylist(track: AudioTrack) {
        val c = collection ?: return
        val entry = track.playlistEntryId ?: return
        if (c.kind != CollectionKind.Playlist) return
        collection = c.copy(tracks = c.tracks.filterNot { it.playlistEntryId == entry })
        scope.launch {
            try {
                api.removeFromPlaylist(c.id, listOf(entry))
                toast("Retiré de « ${c.title} »")
                refreshPlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(friendly(e))
                reloadCollection()
            }
        }
    }

    private suspend fun refreshPlaylists() {
        val lists = safe(playlists) { api.playlists() }
        playlists = lists
        (music as? MusicHomeState.Ready)?.let { music = it.copy(playlists = lists) }
    }

    fun ensurePlaylists() {
        if (playlists.isEmpty()) scope.launch { refreshPlaylists() }
    }

    // ------------------------------------------------------------ Livres audio

    fun loadBooks(quiet: Boolean = false) {
        val keep = quiet && books is BooksState.Ready
        if (!keep) books = BooksState.Loading
        booksJob?.cancel()
        booksJob = scope.launch {
            try {
                books = BooksState.Ready(api.books())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!keep) books = BooksState.Error(friendly(e))
            }
        }
    }

    fun openBook(book: AudioBook) {
        bookId = book.id
    }

    /** Livres triés et filtrés pour la grille. */
    fun visibleBooks(all: List<AudioBook>): List<AudioBook> {
        val term = bookQuery.trim()
        val filtered = if (term.isEmpty()) all else all.filter {
            it.title.contains(term, ignoreCase = true) || it.author?.contains(term, ignoreCase = true) == true
        }
        return when (bookSort) {
            BookSort.Recent -> filtered.sortedByDescending { it.dateCreatedMs ?: 0L }
            BookSort.Az -> filtered.sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            BookSort.Author -> filtered.sortedWith(compareBy<AudioBook>({ it.author?.lowercase() ?: "￿" }, { it.title.lowercase() }))
        }
    }

    // ------------------------------------------------------------ Divers

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(2_600)
            message = null
        }
    }

    fun reset() {
        music = MusicHomeState.Loading
        playlists = emptyList()
        collection = null
        query = ""
        search = null
        style = null
        books = BooksState.Loading
        bookId = null
        addingTracks = null
        musicScroll = 0 to 0
        musicFocusKey = null
        booksScroll = 0 to 0
        booksFocusKey = null
    }

    private suspend fun <T> safe(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

    private companion object {
        val Accents = Regex("\\p{Mn}+")
    }
}
