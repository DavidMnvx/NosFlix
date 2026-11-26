package fr.nosflix.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nosflix.app.audio.AudioEngine
import fr.nosflix.app.audio.AudioLibrary
import fr.nosflix.app.data.AudioAlbum
import fr.nosflix.app.data.AudioBook
import fr.nosflix.app.data.AudioPlaylist
import fr.nosflix.app.data.AudioTrack
import fr.nosflix.app.data.BrowseFilter
import fr.nosflix.app.data.BrowseKey
import fr.nosflix.app.data.BrowseState
import fr.nosflix.app.data.DetailState
import fr.nosflix.app.data.HomeState
import fr.nosflix.app.data.JellyfinClient
import fr.nosflix.app.data.JellyfinException
import fr.nosflix.app.data.KidsApi
import fr.nosflix.app.data.KidsConfig
import fr.nosflix.app.data.Profile
import fr.nosflix.app.data.MainScreen
import fr.nosflix.app.data.MediaItem
import fr.nosflix.app.data.MediaRow
import fr.nosflix.app.data.MoreLink
import fr.nosflix.app.data.SearchHit
import fr.nosflix.app.data.SeasonInfo
import fr.nosflix.app.data.withPlayed
import fr.nosflix.app.data.Session
import fr.nosflix.app.data.ServerRoute
import fr.nosflix.app.data.SessionStore
import fr.nosflix.app.data.SortOption
import fr.nosflix.app.data.TmdbClient
import fr.nosflix.app.data.TmdbDetails
import fr.nosflix.app.data.TmdbTitle
import fr.nosflix.app.data.WishItem
import fr.nosflix.app.data.WishlistApi
import fr.nosflix.app.data.plural
import fr.nosflix.app.data.tmdbKey
import fr.nosflix.app.data.toExternalItem
import fr.nosflix.app.data.toWishItem
import fr.nosflix.app.data.PlayWarning
import fr.nosflix.app.data.PlayedConfirm
import fr.nosflix.app.player.DeviceCodecs
import fr.nosflix.app.player.ExternalPlayer
import fr.nosflix.app.update.UpdateInfo
import fr.nosflix.app.update.Updater
import fr.nosflix.app.player.PlayerActivity
import fr.nosflix.app.ui.episodeLine
import fr.nosflix.app.ui.metaLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Dates de sortie et plateformes de la liste « À voir plus tard » : remises à jour au plus une fois par jour. */
private const val WISH_REFRESH_MS = 20L * 60 * 60 * 1000

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NosFlixApp
    val client: JellyfinClient = app.client
    private val store: SessionStore = app.store
    private val tmdb: TmdbClient = app.tmdb
    /** Tendances et actualité TMDB : pas pour un profil enfant (affiches et titres non filtrés). */
    private val tmdbOn: Boolean get() = tmdb.enabled && !isKid
    val trendsEnabled: Boolean get() = tmdbOn

    // ------------------------------------------------------------ Musique et livres audio
    /** Lecteur audio partagé (il continue quand on quitte l'écran). */
    val engine: AudioEngine get() = app.audio
    val audio = AudioLibrary(viewModelScope, app.audioApi) { it.friendly() }
    /** Lecteur audio plein écran ouvert. */
    var showNowPlaying by mutableStateOf(false)
    private var collectionReturn: MainScreen = MainScreen.Music

    // ------------------------------------------------------------ Connexion
    var session by mutableStateOf(store.load())
        private set
    var loggingIn by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    val lastServer: String? get() = store.lastServer
    val lastUser: String? get() = store.lastUser

    // ------------------------------------------------------------ Profils (un compte Jellyfin chacun)
    private val kidsApi = KidsApi(client)
    /** Profils de la maison, pour l'écran « Qui regarde ? ». */
    var profiles by mutableStateOf(store.profiles())
        private set
    /** Profils enfants et titres que les parents leur ont autorisés. */
    var kids by mutableStateOf(store.kids())
        private set
    /** Écran « Qui regarde ? » affiché. */
    var choosingProfile by mutableStateOf(false)
        private set
    var profileBusy by mutableStateOf(false)
        private set
    /** Un compte parent est connu sur cet appareil : on peut créer des profils et choisir les titres des enfants. */
    var canManageProfiles by mutableStateOf(store.adminToken != null)
        private set
    /** Le profil en cours est un profil enfant. */
    val isKid: Boolean get() = session?.userId?.let { it in kids.users } == true
    val currentProfile: Profile?
        get() = session?.let { s -> profiles.firstOrNull { it.id == s.userId } ?: Profile(s.userId, s.userName, isAdmin = false, hasPassword = false) }

    // ------------------------------------------------------------ Réglages
    var externalPlayer by mutableStateOf(store.externalPlayer)
        private set
    var showSettings by mutableStateOf(false)

    // ------------------------------------------------------------ Navigation et contenu
    var screen by mutableStateOf<MainScreen>(MainScreen.Home)
        private set
    var home by mutableStateOf<HomeState>(HomeState.Loading)
        private set
    var genres by mutableStateOf<List<String>>(emptyList())
        private set
    /** Genres des films (sous-menu « Films ») et des séries. */
    var movieGenres by mutableStateOf<List<String>>(emptyList())
        private set
    var showGenres by mutableStateOf<List<String>>(emptyList())
        private set
    /** Dernière affiche sélectionnée dans l'onglet Actualité (TV). */
    var newsFocusKey: String? = null
    /** Dernière affiche sélectionnée sur les pages Films et Séries (TV). */
    val browseFocus = mutableMapOf<String, String>()
    /** Fenêtre « Quitter NosFlix ? » ouverte. */
    var askExit by mutableStateOf(false)

    // ------------------------------------------------------------ Mises à jour (depuis le serveur)
    /** Nouvelle version trouvée sur le serveur (fenêtre « Mise à jour disponible »). */
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    /** Téléchargement en cours : 0..1 (null = pas de téléchargement). */
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    private var updateDismissed = false

    /** Au démarrage (en silence) ou depuis les paramètres ([manual] : on dit aussi « à jour »). */
    fun checkForUpdate(manual: Boolean = false) {
        val address = server ?: return
        viewModelScope.launch {
            val found = try {
                Updater.check(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) audio.toast("Le dossier des mises à jour du serveur ne répond pas.")
                return@launch
            }
            when {
                found != null && (manual || !updateDismissed) -> update = found
                found == null && manual -> audio.toast("NosFlix est à jour (version ${Updater.currentName}).")
            }
        }
    }

    fun dismissUpdate() {
        update = null
        updateDismissed = true
    }

    /** Télécharge la nouvelle version puis laisse Android demander la confirmation. */
    fun installUpdate(context: Context) {
        val info = update ?: return
        if (updateProgress != null) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                updateProgress = 0f
                val apk = Updater.download(appContext, info) { p ->
                    if (p >= 1f || p - (updateProgress ?: 0f) >= 0.01f) updateProgress = p
                }
                Updater.install(appContext, apk)
                update = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.toast(e.message ?: "Téléchargement de la mise à jour impossible.")
            } finally {
                updateProgress = null
            }
        }
    }

    /**
     * Titres que cet appareil ne sait pas lire de façon fluide : id → format (« AV1 »).
     * Une série est marquée si la plupart de ses épisodes le sont.
     */
    var formatBadges by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private fun loadFormatBadges() {
        viewModelScope.launch {
            val codecs = safe(emptyList()) { client.videoCodecs() }
            if (codecs.isEmpty()) return@launch
            val badges = HashMap<String, String>()
            codecs.forEach { (id, _, codec) -> DeviceCodecs.problem(codec)?.let { badges[id] = it } }
            codecs.filter { it.second != null }.groupBy { it.second!! }.forEach { (seriesId, episodes) ->
                val bad = episodes.mapNotNull { DeviceCodecs.problem(it.third) }
                if (bad.isNotEmpty() && bad.size * 2 >= episodes.size) badges[seriesId] = bad.first()
            }
            formatBadges = badges
        }
    }
    var browse by mutableStateOf<BrowseState?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    /** Résultats TMDB qui ne sont pas dans la bibliothèque. */
    var externalResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    /** Dernière affiche sélectionnée sur l'accueil TV (« ligne:titre »), pour y revenir au retour. */
    var homeFocusKey: String? = null

    /** Fiche détaillée ouverte. */
    var detail by mutableStateOf<DetailState?>(null)
        private set
    private val detailStack = mutableListOf<MediaItem>()
    private var detailReturn: MainScreen = MainScreen.Home

    /** Liste d'envies (« À voir plus tard »), accordée avec le serveur de la maison. */
    var wishlist by mutableStateOf<List<WishItem>>(emptyList())
        private set
    private val wishApi = WishlistApi(client)
    private var wishSyncing = false
    private var wishAgain = false
    /** La bibliothèque a été lue au moins une fois (pour marquer « déjà dans NosFlix »). */
    private var libraryLoaded = false

    /** Bibliothèque indexée par clé TMDB (« movie:123 », « tv:456 ») et par identifiant Jellyfin. */
    private var libraryByTmdb: Map<String, MediaItem> = emptyMap()
    private var libraryById: Map<String, MediaItem> = emptyMap()

    private var homeJob: Job? = null
    private var browseJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var seasonJob: Job? = null

    /** Fenêtre « format difficile pour cet appareil » avant une lecture. */
    var playWarning by mutableStateOf<PlayWarning?>(null)
        private set

    // Déclarés avant init, qui s'en sert.
    private val connectivity: ConnectivityManager? = application.getSystemService(ConnectivityManager::class.java)
    private var networkJob: Job? = null

    /** Wi-Fi ↔ 4G, Tailscale allumé ou éteint : on revérifie par où joindre le serveur. */
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
    }

    init {
        session?.let { s ->
            store.migrateWishlist(s.userId)
            store.saveProfileToken(s.userId, s.token)
            wishlist = store.wishlist(s.userId)
            // Plusieurs profils : « Qui regarde ? » d'abord, l'accueil se charge une fois le profil choisi.
            choosingProfile = profiles.size >= 2
            viewModelScope.launch {
                // À la maison ou ailleurs (Tailscale) : on trouve d'abord par où joindre le serveur.
                client.reroute()
                followServer()
                if (!choosingProfile) startProfile()
                checkForUpdate()
                refreshProfiles()
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkWatch) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkWatch) }
        super.onCleared()
    }

    // ------------------------------------------------------------ Par où joindre le serveur

    private fun networkChanged() {
        viewModelScope.launch {
            networkJob?.cancel()
            networkJob = launch {
                delay(1_500)
                val before = client.session?.server ?: return@launch
                val now = client.reroute(minGapMs = 3_000) ?: return@launch
                followServer()
                // Les affiches déjà affichées pointent vers l'ancienne adresse : on recharge l'accueil.
                if (now != before && !choosingProfile) loadHome(quiet = true)
            }
        }
    }

    /** L'adresse où le serveur répond (elle change quand on quitte la maison ou qu'on y revient). */
    private val server: String? get() = client.session?.server ?: session?.server

    /** Recopie dans [session] l'adresse que le client a trouvée. */
    private fun followServer() {
        val found = client.session?.server ?: return
        session?.let { s -> if (s.server != found) session = s.copy(server = found) }
    }

    // ------------------------------------------------------------ Profils

    /** Ouvre le profil en cours : filtre enfant d'abord s'il le faut, puis l'accueil. */
    private fun startProfile() {
        val kidNow = isKid
        // Profil enfant : rien n'est visible tant que la liste de ses titres n'est pas prête.
        client.kidAllowed = if (kidNow) emptySet() else null
        viewModelScope.launch {
            if (kidNow) {
                refreshKids()
                applyKidFilter()
                loadHome()
            } else {
                loadHome()
                refreshKids()
                // Devenu profil enfant depuis un autre appareil.
                if (isKid) {
                    applyKidFilter()
                    loadHome()
                }
            }
        }
    }

    private suspend fun refreshKids() {
        if (session == null) return
        safe<KidsConfig?>(null) { kidsApi.fetch() }?.let {
            kids = it
            store.saveKids(it)
        }
    }

    /** Profil enfant : les titres autorisés (choisis par les parents ou tous publics) sont passés au client Jellyfin. */
    private suspend fun applyKidFilter() {
        if (!isKid) {
            client.kidAllowed = null
            return
        }
        val all = safe<List<MediaItem>?>(null) { client.items(types = "Movie,Series,Video", limit = 10000, unfiltered = true) }
        client.kidAllowed = all?.filter { kids.allows(it) }?.mapTo(HashSet()) { it.id } ?: emptySet()
    }

    /** Liste des profils ; le premier compte parent connecté sur l'appareil sert à les gérer. */
    fun refreshProfiles() {
        viewModelScope.launch { refreshProfilesNow() }
    }

    private suspend fun refreshProfilesNow() {
        val s = session ?: return
        var token = store.adminToken ?: s.token
        var list = safe<List<Profile>?>(null) { client.users(token) }
        if (list == null && token != s.token) {
            token = s.token
            list = safe<List<Profile>?>(null) { client.users(token) }
        }
        // Pas de compte parent (ou serveur injoignable) : on garde la liste connue.
        if (list == null) return
        if (store.adminToken != token) store.adminToken = token
        canManageProfiles = true
        profiles = list
        store.saveProfiles(list)
        refreshKids()
    }

    fun showProfiles() {
        showSettings = false
        choosingProfile = true
        refreshProfiles()
    }

    /** « Qui regarde ? » fermé sans changer : on reste sur le profil en cours. */
    fun closeProfiles() {
        if (!choosingProfile) return
        choosingProfile = false
        if (home is HomeState.Loading && homeJob == null) startProfile()
    }

    fun chooseProfile(p: Profile) {
        val s = session ?: return
        if (profileBusy) return
        if (p.id == s.userId) {
            closeProfiles()
            return
        }
        profileBusy = true
        viewModelScope.launch {
            try {
                // Jeton déjà connu sur l'appareil (encore valable), sinon connexion sans mot de passe.
                val address = server ?: s.server
                val known = store.profileToken(p.id)?.takeIf { client.tokenValid(address, it) }
                val next = when {
                    known != null -> Session(server ?: address, p.id, known, p.name)
                    !p.hasPassword -> client.login(address, p.name, "")
                    else -> {
                        audio.toast("Ce profil a un mot de passe : ouvre-le une fois avec Paramètres → Se déconnecter.")
                        return@launch
                    }
                }
                switchTo(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.toast("Impossible d’ouvrir ce profil : ${e.friendly()}")
            } finally {
                profileBusy = false
            }
        }
    }

    /** Tout ce qui était affiché appartenait au profil précédent : on repart de l'accueil du nouveau. */
    private fun switchTo(next: Session) {
        engine.stop()
        audio.reset()
        showNowPlaying = false
        store.save(next)
        client.session = next
        session = next
        homeJob?.cancel()
        homeJob = null
        home = HomeState.Loading
        browseJob?.cancel()
        browse = null
        browseStack.clear()
        detailJob?.cancel()
        detail = null
        detailStack.clear()
        searchJob?.cancel()
        results = emptyList()
        externalResults = emptyList()
        query = ""
        screen = MainScreen.Home
        homeFocusKey = null
        newsFocusKey = null
        browseFocus.clear()
        libraryLoaded = false
        libraryByTmdb = emptyMap()
        libraryById = emptyMap()
        wishlist = store.wishlist(next.userId)
        choosingProfile = false
        startProfile()
    }

    /** Nouveau profil (compte Jellyfin sans mot de passe), éventuellement enfant. */
    fun addProfile(name: String, kid: Boolean, onDone: () -> Unit) {
        val s = session ?: return
        val admin = store.adminToken ?: return
        val clean = name.trim().replace(Regex("\\s+"), " ")
        when {
            clean.isEmpty() -> return audio.toast("Écris le prénom du profil.")
            clean.length > 20 -> return audio.toast("Prénom trop long (20 lettres au plus).")
            profiles.any { it.name.equals(clean, ignoreCase = true) } -> return audio.toast("Il y a déjà un profil « $clean ».")
        }
        if (profileBusy) return
        profileBusy = true
        viewModelScope.launch {
            try {
                client.createProfile(clean, admin)
                val login = client.login(server ?: s.server, clean, "")
                store.saveProfileToken(login.userId, login.token)
                if (kid) saveKidsNow(kids.copy(users = kids.users + login.userId))
                refreshProfilesNow()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.toast("Profil non créé : ${e.friendly()}")
            } finally {
                profileBusy = false
            }
        }
    }

    /** Profil enfant ou non (un compte parent ne peut pas devenir enfant). */
    fun setProfileKid(p: Profile, kid: Boolean) {
        if (p.isAdmin) return
        viewModelScope.launch {
            val users = if (kid) kids.users + p.id else kids.users - p.id
            saveKidsNow(kids.copy(users = users))
            if (p.id == session?.userId) {
                applyKidFilter()
                loadHome()
            }
        }
    }

    /** Supprime le profil et son compte Jellyfin (son historique part avec lui). */
    fun deleteProfile(p: Profile, onDone: () -> Unit) {
        val admin = store.adminToken ?: return
        if (p.isAdmin || p.id == session?.userId || profileBusy) return
        profileBusy = true
        viewModelScope.launch {
            try {
                client.deleteProfile(p.id, admin)
                store.removeProfileToken(p.id)
                if (p.id in kids.users) saveKidsNow(kids.copy(users = kids.users - p.id))
                refreshProfilesNow()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.toast("Profil non supprimé : ${e.friendly()}")
            } finally {
                profileBusy = false
            }
        }
    }

    /** Parents : ce titre est-il visible pour les profils enfants ? */
    fun isKidAllowed(item: MediaItem): Boolean = kids.allows(item)

    /** Bouton « Autorisé aux enfants » : sur les profils adultes, quand un profil enfant existe. */
    val canChooseKidTitles: Boolean get() = canManageProfiles && !isKid && kids.users.isNotEmpty()

    fun toggleKidAllowed(item: MediaItem) {
        val updated = if (kids.allows(item)) {
            kids.copy(allowed = kids.allowed - item.id, blocked = kids.blocked + item.id)
        } else {
            kids.copy(allowed = kids.allowed + item.id, blocked = kids.blocked - item.id)
        }
        viewModelScope.launch { saveKidsNow(updated) }
    }

    private suspend fun saveKidsNow(updated: KidsConfig) {
        val admin = store.adminToken ?: return
        kids = updated
        store.saveKids(updated)
        try {
            kidsApi.save(updated, admin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pas de service NosFlix sur le serveur : les réglages restent sur cet appareil, sans message.
            if (e !is ConnectException) audio.toast("Pas enregistré sur le serveur : ${e.friendly()}")
        }
    }

    // ------------------------------------------------------------ Réglages : adresse hors de la maison, clé TMDB

    /** Paramètres → « Adresse hors de la maison » (vide : aucune). */
    var awayServer by mutableStateOf(store.awayServer)
        private set

    fun saveAwayServer(input: String) {
        val clean = input.trim().takeIf { it.isNotEmpty() }?.let(JellyfinClient::normalizeServer).orEmpty()
        store.awayServer = clean
        ServerRoute.away = clean
        awayServer = clean
        // On revérifie tout de suite par où joindre le serveur.
        viewModelScope.launch {
            client.reroute()
            followServer()
        }
    }

    /** La clé TMDB vient de local.properties : pas besoin d'en coller une dans les Paramètres. */
    val tmdbBuiltIn: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    var tmdbKey by mutableStateOf(store.tmdbKey)
        private set

    fun saveTmdbKey(input: String) {
        if (tmdbBuiltIn) return
        val clean = input.trim()
        store.tmdbKey = clean
        tmdb.key = clean
        tmdbKey = clean
        loadHome(quiet = true)
    }

    // ------------------------------------------------------------ Actions : compte

    fun login(server: String, user: String, password: String) {
        if (loggingIn) return
        if (user.isBlank()) {
            loginError = "Entre ton nom d’utilisateur Jellyfin."
            return
        }
        loginError = null
        loggingIn = true
        viewModelScope.launch {
            try {
                val newSession = client.authenticate(server, user.trim(), password)
                store.save(newSession)
                session = newSession
                screen = MainScreen.Home
                wishlist = store.wishlist(newSession.userId)
                startProfile()
                checkForUpdate()
                refreshProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError = e.friendly()
            } finally {
                loggingIn = false
            }
        }
    }

    fun logout() {
        engine.stop()
        audio.reset()
        showNowPlaying = false
        store.clear()
        client.session = null
        client.kidAllowed = null
        session = null
        showSettings = false
        choosingProfile = false
        canManageProfiles = false
        profiles = emptyList()
        wishlist = emptyList()
        homeJob?.cancel()
        homeJob = null
        home = HomeState.Loading
        browse = null
        detail = null
        results = emptyList()
        externalResults = emptyList()
        query = ""
        screen = MainScreen.Home
    }

    fun updateExternalPlayer(enabled: Boolean) {
        store.externalPlayer = enabled
        externalPlayer = enabled
    }

    // ------------------------------------------------------------ Actions : navigation

    fun goHome() {
        screen = MainScreen.Home
    }

    /** Bouton Retour : on remonte les fiches ouvertes, puis on revient à l'écran d'avant, puis à l'accueil. */
    fun back() {
        if (screen == MainScreen.Detail) {
            val previous = detailStack.removeLastOrNull()
            if (previous != null) {
                loadDetail(previous)
            } else {
                detailJob?.cancel()
                detail = null
                screen = detailReturn
            }
        } else if (screen == MainScreen.Browse && browseStack.isNotEmpty()) {
            // Retour d'une grille « Voir tout » : la page Films ou Séries telle qu'on l'a laissée.
            browseJob?.cancel()
            browse = browseStack.removeAt(browseStack.lastIndex)
        } else {
            screen = when (screen) {
                MainScreen.MusicCollection -> collectionReturn
                MainScreen.Book -> MainScreen.Books
                else -> MainScreen.Home
            }
        }
    }

    // ------------------------------------------------------------ Actions : musique et livres audio

    fun openMusic() {
        screen = MainScreen.Music
        audio.loadMusic(quiet = true)
    }

    fun openAlbum(album: AudioAlbum) {
        if (screen != MainScreen.MusicCollection) collectionReturn = screen
        audio.openAlbum(album)
        screen = MainScreen.MusicCollection
    }

    fun openAlbumOf(track: AudioTrack) {
        if (track.albumId == null) return
        if (screen != MainScreen.MusicCollection) collectionReturn = screen
        audio.openAlbumOf(track)
        screen = MainScreen.MusicCollection
        showNowPlaying = false
    }

    fun openPlaylist(playlist: AudioPlaylist) {
        if (screen != MainScreen.MusicCollection) collectionReturn = screen
        audio.openPlaylist(playlist)
        screen = MainScreen.MusicCollection
    }

    fun openLiked() {
        if (screen != MainScreen.MusicCollection) collectionReturn = screen
        audio.openLiked()
        screen = MainScreen.MusicCollection
    }

    fun openBooks() {
        screen = MainScreen.Books
        audio.loadBooks(quiet = true)
    }

    fun openBook(book: AudioBook) {
        audio.openBook(book)
        screen = MainScreen.Book
    }

    /** Reprend un livre là où tu t'étais arrêté (ou le continue s'il est déjà chargé). */
    fun resumeBook(book: AudioBook) {
        if (engine.active && engine.book?.id == book.id) {
            if (!engine.playing) engine.toggle()
        } else {
            val (part, position) = bookResume(book)
            engine.playBook(book, part, position)
        }
        showNowPlaying = true
    }

    /** Position gardée sur l'appareil, si elle est plus récente que celle du serveur. */
    private fun localPoint(book: AudioBook): Pair<Int, Long>? {
        val (part, position, savedAt) = engine.savedPoint(book.id) ?: return null
        if (part !in book.parts.indices) return null
        if (savedAt < (book.lastPlayedMs ?: 0L) - 5_000) return null
        return part to position
    }

    /** Où reprendre un livre : (partie, position). */
    fun bookResume(book: AudioBook): Pair<Int, Long> = localPoint(book) ?: book.resumePoint()

    /** Temps déjà écouté dans un livre. */
    fun bookListened(book: AudioBook): Long {
        val (part, position) = localPoint(book) ?: return book.listenedMs
        return maxOf(book.listenedMs, book.parts.take(part).sumOf { it.durationMs } + position)
    }

    fun bookStarted(book: AudioBook): Boolean = book.started || localPoint(book) != null

    fun bookInProgress(book: AudioBook): Boolean = !book.finished && bookStarted(book)

    /** Pour trier « Continuer l'écoute » : la dernière écoute, sur le serveur ou sur l'appareil. */
    fun bookLastListen(book: AudioBook): Long =
        maxOf(book.lastPlayedMs ?: 0L, engine.savedPoint(book.id)?.third ?: 0L)

    fun openNowPlaying() {
        if (engine.active) showNowPlaying = true
    }

    /** Cœurs changés pendant la session (les listes déjà chargées ne sont pas rechargées). */
    private val trackFavorites = mutableStateMapOf<String, Boolean>()

    fun isFavorite(track: AudioTrack): Boolean = trackFavorites[track.id] ?: track.favorite

    fun toggleTrackFavorite(track: AudioTrack) {
        val now = !isFavorite(track)
        trackFavorites[track.id] = now
        viewModelScope.launch {
            safe(Unit) { client.setFavorite(track.id, now) }
            audio.toast(if (now) "Ajouté aux titres likés" else "Retiré des titres likés")
        }
    }

    fun openSearch() {
        screen = MainScreen.Search
        if (results.isEmpty()) onQuery(query)
    }

    /** Pages (Films, Séries…) d'où l'on a ouvert un « Voir tout » : Retour y ramène. */
    private val browseStack = mutableListOf<BrowseState>()

    /** Une catégorie choisie dans le menu ou les onglets. */
    fun openBrowse(key: BrowseKey, sort: SortOption = SortOption.Smart) {
        browseStack.clear()
        loadBrowse(key, sort)
    }

    /** « Voir tout » d'une ligne : la grille complète ; Retour revient à la page d'où l'on vient. */
    fun openMore(link: MoreLink) {
        if (screen == MainScreen.Browse) browse?.let { browseStack.add(it) } else browseStack.clear()
        loadBrowse(link.key, link.sort)
    }

    private fun loadBrowse(key: BrowseKey, sort: SortOption) {
        screen = MainScreen.Browse
        browse = BrowseState(key, sort, loading = true)
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            try {
                val pool = client.items(
                    types = key.types,
                    sortBy = sort.sortBy,
                    sortOrder = sort.order,
                    limit = 1000,
                    genre = (key as? BrowseKey.Genre)?.name,
                    filters = if (key == BrowseKey.MyList) "IsFavorite" else null,
                    is4K = if (key == BrowseKey.Uhd) true else null,
                )
                val sortedAll = if (sort == SortOption.Smart) pool.sortedByDescending { it.smartScore() } else pool
                // Grille « Voir tout » d'une ligne : seulement les titres de cette ligne.
                val sorted = when ((key as? BrowseKey.Section)?.filter) {
                    BrowseFilter.Unseen -> sortedAll.filter { !it.played && !it.inProgress }
                    BrowseFilter.Short -> sortedAll.filter { (it.runtimeMinutes ?: 0) in 1..90 }
                    else -> sortedAll
                }
                // Films et Séries : chaque ligne a son « Voir tout » (grille complète).
                val scope = when (key) {
                    BrowseKey.Movies -> "Movie"
                    BrowseKey.Shows -> "Series"
                    else -> null
                }
                val series = scope == "Series"
                fun more(label: String, filter: BrowseFilter) = scope?.let { MoreLink(BrowseKey.Section(label, it, filter), sort) }
                val rows = buildList {
                    add(MediaRow("all", "Tous les titres", "Tri : ${sort.label}", sorted, more = more(if (series) "Toutes les séries" else "Tous les films", BrowseFilter.All)))
                    val unseen = sorted.filter { !it.played && !it.inProgress }
                    if (unseen.isNotEmpty() && unseen.size < sorted.size) {
                        add(MediaRow("unseen", "Pas encore vus", plural(unseen.size, "titre", "titres"), unseen, more = more(if (series) "Séries pas encore vues" else "Films pas encore vus", BrowseFilter.Unseen)))
                    }
                    val short = sorted.filter { it.isMovie && (it.runtimeMinutes ?: 0) in 1..90 }
                    if (short.isNotEmpty() && short.size < sorted.size) {
                        add(MediaRow("short", "Pour ce soir", "moins d’1 h 30", short, more = more("Films de moins d’1 h 30", BrowseFilter.Short)))
                    }
                    // Films et Séries : une ligne par genre (Animation, Science-fiction, Comédie…).
                    if (scope != null) {
                        genresOf(sorted).take(14).forEach { g ->
                            val inGenre = sorted.filter { g in it.genres }
                            add(MediaRow("g-$g", g, plural(inGenre.size, "titre", "titres"), inGenre, more = MoreLink(BrowseKey.Genre(g, scope), SortOption.Smart)))
                        }
                    }
                }.filter { it.items.isNotEmpty() }
                val movies = sorted.count { it.isMovie }
                val shows = sorted.count { it.isSeries }
                val count = when {
                    sorted.isEmpty() -> ""
                    key is BrowseKey.Section || key is BrowseKey.Genre && key.scope != "Movie,Series" -> plural(sorted.size, "titre", "titres")
                    else -> plural(sorted.size, "titre", "titres") + " · " + plural(movies, "film", "films") + ", " + plural(shows, "série", "séries")
                }
                browse = BrowseState(key, sort, loading = false, rows = rows, count = count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                browse = BrowseState(key, sort, loading = false, error = e.friendly())
            }
        }
    }

    fun setSort(sort: SortOption) {
        browse?.let { loadBrowse(it.key, sort) }
    }

    // ------------------------------------------------------------ Accueil

    fun loadHome(quiet: Boolean = false) {
        if (session == null) return
        homeJob?.cancel()
        val keepCurrent = quiet && home is HomeState.Ready
        if (!keepCurrent) home = HomeState.Loading
        // À chaque rechargement : un format qui vient d'être bien lu ici perd sa vignette.
        loadFormatBadges()
        homeJob = viewModelScope.launch {
            try {
                home = buildHome()
                // Liste « À voir plus tard » : changements des autres appareils, « déjà dans NosFlix », dates.
                syncWishlist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!keepCurrent) home = HomeState.Error(e.friendly())
            }
        }
    }

    /** Après une lecture : on rafraîchit « Reprendre la lecture » sans écran de chargement. */
    fun refreshAfterPlayback() {
        if (session != null) loadHome(quiet = true)
    }

    private suspend fun buildHome(): HomeState.Ready = coroutineScope {
        val recentD = async { client.items(sortBy = "DateCreated", sortOrder = "Descending", limit = 20) }
        val previous = home as? HomeState.Ready
        val resumeD = async { safe<List<MediaItem>?>(null) { client.resume() } }
        val nextUpD = async { safe<List<MediaItem>?>(null) { client.nextUp(20) } }
        val topPlayedD = async { safe(emptyList()) { client.items(types = "Movie", sortBy = "PlayCount", sortOrder = "Descending", filters = "IsPlayed", limit = 10) } }
        val ratedD = async { safe(emptyList()) { client.items(sortBy = "CommunityRating", sortOrder = "Descending", limit = 20) } }
        val moviesRatedD = async { safe(emptyList()) { client.items(types = "Movie", sortBy = "CommunityRating", sortOrder = "Descending", limit = 150) } }
        val uhdD = async { safe(emptyList()) { client.items(types = "Movie", is4K = true, sortBy = "CommunityRating", sortOrder = "Descending", limit = 20) } }
        val playedD = async { safe(emptyList()) { client.items(types = "Movie,Episode", sortBy = "DatePlayed", sortOrder = "Descending", filters = "IsPlayed", limit = 12) } }
        val genresD = async { safe(emptyList()) { client.genres() } }
        val indexD = async { safe(emptyList()) { client.items(types = "Movie,Series", limit = 5000) } }

        // TMDB (seulement si la clé est là) : en parallèle de Jellyfin.
        val useTmdb = tmdbOn
        val trendingD = async { if (useTmdb) safe(emptyList()) { tmdb.trending(isTv = false, limit = 10) } else emptyList() }
        val nowPlayingD = async { if (useTmdb) safe(emptyList()) { tmdb.nowPlaying() } else emptyList() }
        val upcomingD = async { if (useTmdb) safe(emptyList()) { tmdb.upcoming() } else emptyList() }
        val trendingTvD = async { if (useTmdb) safe(emptyList()) { tmdb.trending(isTv = true, limit = 20) } else emptyList() }

        val library = indexD.await()
        // La liste des genres de Jellyfin n'est prête qu'en fin d'analyse : sinon on la tire des titres eux-mêmes.
        val allGenres = genresD.await().ifEmpty { genresOf(library) }
        genres = allGenres
        val genreRowsD = allGenres.take(8).map { g ->
            async { g to safe(emptyList()) { client.items(genre = g, sortBy = "CommunityRating", sortOrder = "Descending", limit = 20) } }
        }

        libraryByTmdb = library.mapNotNull { item -> item.tmdbKey?.let { it to item } }.toMap()
        libraryById = library.associateBy { it.id }
        if (library.isNotEmpty()) libraryLoaded = true

        val played = playedD.await()
        val last = played.firstOrNull()
        val similar = if (last != null) safe(emptyList()) { client.similar(last.seriesId ?: last.id) } else emptyList()

        val recent = recentD.await()
        val topPlayed = topPlayedD.await()
        val rated = ratedD.await()
        val uhd = uhdD.await()
        val tonight = moviesRatedD.await().filter { (it.runtimeMinutes ?: 0) in 1..90 }.take(20)
        // Serveur trop occupé (analyse en cours) : on garde la ligne « Reprendre » d'avant plutôt qu'une ligne vide.
        val resumeNew = resumeD.await()
        val nextUpNew = nextUpD.await()
        val resumeItems = if (resumeNew == null && nextUpNew == null) {
            previous?.rows?.firstOrNull { it.id == "resume" }?.items.orEmpty()
        } else {
            (resumeNew.orEmpty() + nextUpNew.orEmpty()).distinctBy { it.id }
        }
        val forYou = if (useTmdb) recommendationsFor(played + resumeItems, rated) else null

        val trending = trendingD.await().map { resolve(it) }
        val nowPlaying = nowPlayingD.await().map { resolve(it, "AU CINÉMA") }
        val upcoming = upcomingD.await().map { resolve(it, TmdbClient.shortDate(it.releaseDate)?.let { d -> "LE $d" } ?: "BIENTÔT") }
        val trendingTv = trendingTvD.await().map { resolve(it) }
        val genreRows = genreRowsD.awaitAll()

        val rows = buildList {
            if (resumeItems.isNotEmpty()) add(MediaRow("resume", "Reprendre la lecture", items = resumeItems))
            // Profil enfant : tous ses programmes d'un coup d'œil (la liste choisie par les parents).
            if (isKid && library.isNotEmpty()) {
                add(MediaRow("kids", "Tes programmes", plural(library.size, "titre", "titres"), library.sortedByDescending { it.smartScore() }))
            }
            if (trending.isNotEmpty()) {
                // Classement mondial de la semaine (TMDB) ; les films déjà chez toi restent lisibles.
                add(MediaRow("trending", "Tendances mondiales", "le top 10 de la semaine", trending, top = true))
            } else {
                val houseTop = topPlayed.size >= 3
                val top = if (houseTop) topPlayed else rated.take(10)
                if (top.isNotEmpty()) {
                    add(
                        MediaRow(
                            id = "top10",
                            title = if (houseTop) "Top 10 à la maison" else "Top 10 NosFlix",
                            sub = if (houseTop) "les plus regardés" else "les mieux notés",
                            items = top,
                            top = true,
                        ),
                    )
                }
            }
            // L'accueil reste centré sur ta bibliothèque ; l'actualité (TMDB) a son propre onglet.
            if (recent.isNotEmpty()) add(MediaRow("recent", "Ajoutés récemment", items = recent, more = MoreLink(BrowseKey.All, SortOption.Recent)))
            if (uhd.isNotEmpty()) add(MediaRow("uhd", "Films en 4K", items = uhd, more = MoreLink(BrowseKey.Uhd, SortOption.Top)))
            if (last != null && similar.isNotEmpty()) {
                add(MediaRow("similar", "Parce que tu as regardé", "« ${last.seriesName ?: last.name} »", similar))
            }
            if (tonight.isNotEmpty()) add(MediaRow("tonight", "Pour ce soir", "moins d’1 h 30", tonight, more = MoreLink(BrowseKey.Movies, SortOption.Short)))
            if (rated.isNotEmpty()) add(MediaRow("rated", "Les mieux notés", items = rated, more = MoreLink(BrowseKey.All, SortOption.Top)))
            genreRows.forEach { (g, items) ->
                if (items.isNotEmpty()) add(MediaRow("g-$g", g, items = items, more = MoreLink(BrowseKey.Genre(g), SortOption.Smart)))
            }
        }
        // Onglet Actualité : ce qui sort, ce qui buzze, et ce que tu as mis de côté.
        val news = buildList {
            if (nowPlaying.isNotEmpty()) add(MediaRow("now", "À l’affiche en France", "en ce moment au cinéma", nowPlaying))
            if (upcoming.isNotEmpty()) add(MediaRow("upcoming", "Bientôt au cinéma", "les prochaines sorties en France", upcoming))
            if (trendingTv.isNotEmpty()) add(MediaRow("tvtrend", "Séries tendance", "dans le monde cette semaine", trendingTv))
            if (forYou != null && forYou.second.isNotEmpty()) add(MediaRow("foryou", "Tu pourrais aimer", forYou.first, forYou.second))
            wishRow()?.let { add(it) }
        }
        movieGenres = genresOf(library.filter { it.isMovie })
        showGenres = genresOf(library.filter { it.isSeries })
        val hero = recent.firstOrNull { it.backdropItemId != null } ?: recent.firstOrNull()
        HomeState.Ready(hero, rows, news)
    }

    /** Genres présents dans ces titres (au moins 2 titres), du plus courant au plus rare. */
    private fun genresOf(items: List<MediaItem>): List<String> =
        items.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

    fun openNews() {
        screen = MainScreen.News
    }

    /** Un titre TMDB : sa version de la bibliothèque si on l'a, sinon une carte « à découvrir ». */
    private fun resolve(title: TmdbTitle, badge: String? = null): MediaItem =
        libraryByTmdb[title.key] ?: title.toExternalItem(badge)

    /**
     * « Tu pourrais aimer » : recommandations TMDB à partir des derniers titres regardés
     * (ou des mieux notés de la bibliothèque si rien n'a encore été vu).
     * Renvoie (sous-titre de la ligne, titres).
     */
    private suspend fun recommendationsFor(watched: List<MediaItem>, fallback: List<MediaItem>): Pair<String, List<MediaItem>> = coroutineScope {
        fun seriesOf(item: MediaItem): MediaItem = if (item.isEpisode) item.seriesId?.let { libraryById[it] } ?: item else item
        val fromWatched = watched.map(::seriesOf).filter { it.tmdbKey != null }.distinctBy { it.tmdbKey }.take(4)
        val seeds = fromWatched.ifEmpty { fallback.filter { it.tmdbKey != null }.take(3) }
        if (seeds.isEmpty()) return@coroutineScope "" to emptyList()
        val sub = if (fromWatched.isNotEmpty()) "d’après ce que vous regardez" else "d’après tes films les mieux notés"

        val lists = seeds.map { seed ->
            async { safe(emptyList()) { tmdb.recommendations(seed.tmdbId!!, seed.isSeries) } }
        }.awaitAll()
        val seedKeys = seeds.mapNotNull { it.tmdbKey }.toSet()
        // Score : apparaît dans plusieurs listes, puis bien placé dans chaque liste.
        val scores = HashMap<String, Double>()
        val titles = HashMap<String, TmdbTitle>()
        lists.forEach { list ->
            list.forEachIndexed { index, t ->
                if (t.key in seedKeys) return@forEachIndexed
                scores[t.key] = (scores[t.key] ?: 0.0) + 1.0 + (20 - index).coerceAtLeast(0) / 40.0
                titles[t.key] = t
            }
        }
        val items = scores.entries.sortedByDescending { it.value }
            .mapNotNull { titles[it.key] }
            .map { resolve(it) }
            .filterNot { !it.external && it.played }
            .take(20)
        sub to items
    }

    // ------------------------------------------------------------ Fiche détaillée

    fun openDetail(item: MediaItem) {
        if (screen != MainScreen.Detail) {
            detailReturn = screen
            detailStack.clear()
        } else {
            detail?.item?.let { detailStack.add(it) }
        }
        screen = MainScreen.Detail
        loadDetail(item)
    }

    private fun loadDetail(item: MediaItem) {
        val library = if (item.external) item.tmdbKey?.let { libraryByTmdb[it] } else item
        val shown = library ?: item
        val tmdbId = shown.tmdbId
        val withTmdb = tmdbOn && tmdbId != null && !shown.isEpisode
        detail = DetailState(shown, playable = library != null, loading = withTmdb)
        detailJob?.cancel()
        seasonJob?.cancel()
        // Série de la bibliothèque : saisons et épisodes, en partant de la saison du prochain épisode à voir.
        if (library != null && shown.isSeries) {
            seasonJob = viewModelScope.launch {
                val seasonsD = async { safe(emptyList()) { client.seasons(shown.id) } }
                val next = safe<MediaItem?>(null) { client.nextUp(1, seriesId = shown.id).firstOrNull() }
                val seasons = seasonsD.await()
                val start = seasons.firstOrNull { next != null && it.index == next.season }
                    ?: seasons.firstOrNull { (it.index ?: 0) >= 1 }
                    ?: seasons.firstOrNull()
                updateDetail(shown.id) { it.copy(seasons = seasons, seasonId = start?.id, nextUp = next, episodesLoading = start != null) }
                if (start != null) loadEpisodes(shown.id, start.id)
            }
        }
        if (!withTmdb || tmdbId == null) return
        detailJob = viewModelScope.launch {
            val info = safe<TmdbDetails?>(null) { tmdb.details(tmdbId, shown.isSeries) }
            val collection = info?.collectionId?.let { id -> safe(emptyList()) { tmdb.collection(id) } }.orEmpty().map { resolve(it) }
            val similar = info?.recommendations.orEmpty().take(20).map { resolve(it) }
            updateDetail(shown.id) { it.copy(loading = false, info = info, collection = collection, similar = similar) }
        }
    }

    /** Met à jour la fiche affichée, seulement si c'est toujours la même. */
    private fun updateDetail(id: String, change: (DetailState) -> DetailState) {
        val d = detail ?: return
        if (d.item.id == id) detail = change(d)
    }

    private suspend fun loadEpisodes(seriesId: String, seasonId: String) {
        val episodes = safe(emptyList()) { client.episodes(seriesId, seasonId) }
        updateDetail(seriesId) { d -> if (d.seasonId == seasonId) d.copy(episodes = episodes, episodesLoading = false) else d }
    }

    fun selectSeason(seasonId: String) {
        val d = detail ?: return
        if (d.seasonId == seasonId) return
        detail = d.copy(seasonId = seasonId, episodes = emptyList(), episodesLoading = true)
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch { loadEpisodes(d.item.id, seasonId) }
    }

    // ------------------------------------------------------------ Liste d'envies

    fun isWished(item: MediaItem): Boolean {
        val key = item.tmdbKey ?: return false
        return wishlist.any { it.key == key }
    }

    fun toggleWish(item: MediaItem) {
        val userId = session?.userId ?: return
        val key = item.tmdbKey ?: return
        val removing = isWished(item)
        val updated: List<WishItem> = if (removing) {
            wishlist.filterNot { it.key == key }
        } else {
            val now = System.currentTimeMillis()
            // La fiche est ouverte : ses dates et plateformes vont directement dans la liste.
            val info = detail?.info?.takeIf { tmdbKey(it.id, it.isTv) == key }
            val entry = item.toWishItem()?.let { w -> if (info != null) w.withDetails(info, now) else w }
                ?.copy(addedAt = now, inLibrary = key in libraryByTmdb)
                ?: return
            listOf(entry) + wishlist
        }
        wishlist = updated
        store.saveWishlist(userId, updated)
        store.saveWishPending(userId, store.wishPending(userId) + (key to if (removing) SessionStore.WISH_REMOVE else SessionStore.WISH_ADD))
        refreshWishRow()
        syncWishlist()
    }

    /**
     * Le petit service NosFlix (port 8097 : liste partagée, profils enfants, mises à jour) répond.
     * Il est facultatif : sans lui, tout reste sur l'appareil.
     */
    var homeService by mutableStateOf(false)
        private set

    /** Adresse du fichier A-VOIR.txt du serveur (pour les paramètres), si le service NosFlix est là. */
    val wishTextUrl: String? get() = if (homeService) wishApi.textUrl() else null

    /**
     * Accorde la liste de l'appareil avec celle du serveur : on reprend la liste du serveur,
     * on y rejoue les ajouts et retraits faits ici, on met à jour dates et plateformes, puis on la renvoie.
     * Serveur injoignable : la liste reste sur l'appareil et repartira à la prochaine occasion.
     */
    private fun syncWishlist() {
        // Profil enfant : pas de liste d'envies (elle vient des tendances TMDB).
        if (session == null || isKid) return
        if (wishSyncing) {
            wishAgain = true
            return
        }
        wishSyncing = true
        viewModelScope.launch {
            try {
                do {
                    wishAgain = false
                    syncWishlistOnce()
                } while (wishAgain)
            } finally {
                wishSyncing = false
            }
        }
    }

    private suspend fun syncWishlistOnce() {
        val s = session ?: return
        val userId = s.userId
        val pendingBefore = store.wishPending(userId)
        val remote = try {
            wishApi.fetch(s.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        homeService = true
        if (session?.userId != userId) return
        val local = wishlist
        var merged = when {
            // Première fois pour ce profil sur cet appareil : on additionne sa liste et celle du serveur.
            !store.wishSynced(userId) -> mergeWishes(remote.orEmpty(), local, local.associate { it.key to SessionStore.WISH_ADD })
            remote == null -> local
            else -> mergeWishes(remote, local, pendingBefore)
        }
        merged = refreshWishes(merged)

        // On a changé de profil pendant ce temps : cette liste n'est plus la sienne.
        if (session?.userId != userId) return
        // Ajouts ou retraits faits pendant la synchro : on les garde pour le tour suivant.
        val pendingNow = store.wishPending(userId).filter { (k, op) -> pendingBefore[k] != op }
        val final = if (pendingNow.isEmpty()) merged else mergeWishes(merged, wishlist, pendingNow)
        if (final != wishlist) {
            wishlist = final
            store.saveWishlist(userId, final)
            refreshWishRow()
        }
        if (merged != remote) {
            try {
                wishApi.push(merged, s.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
        }
        store.setWishSynced(userId)
        store.saveWishPending(userId, store.wishPending(userId).filter { (k, op) -> pendingBefore[k] != op })
        if (pendingNow.isNotEmpty()) wishAgain = true
    }

    /** [base] + les changements [ops] faits sur la liste [local] (les ajouts passent en tête). */
    private fun mergeWishes(base: List<WishItem>, local: List<WishItem>, ops: Map<String, String>): List<WishItem> {
        val localByKey = local.associateBy { it.key }
        val added = local.filter { ops[it.key] == SessionStore.WISH_ADD }
        val addedKeys = added.mapTo(HashSet()) { it.key }
        val kept = base
            .filter { ops[it.key] != SessionStore.WISH_REMOVE && it.key !in addedKeys }
            // Même titre des deux côtés : on garde la version la plus fraîche (dates, plateformes).
            .map { b -> localByKey[b.key]?.takeIf { it.checkedAt > b.checkedAt } ?: b }
        return added + kept
    }

    /** « Déjà dans NosFlix », puis dates de sortie et plateformes depuis TMDB (une fois par jour). */
    private suspend fun refreshWishes(items: List<WishItem>): List<WishItem> = coroutineScope {
        val now = System.currentTimeMillis()
        val marked = items.map { w ->
            w.copy(
                inLibrary = if (libraryLoaded) w.key in libraryByTmdb else w.inLibrary,
                addedAt = w.addedAt.takeIf { it > 0 } ?: now,
            )
        }
        if (!tmdb.enabled) return@coroutineScope marked
        val stale = marked.filter { now - it.checkedAt > WISH_REFRESH_MS }.take(40)
        if (stale.isEmpty()) return@coroutineScope marked
        val fresh = HashMap<String, TmdbDetails>()
        stale.chunked(5).forEach { chunk ->
            chunk.map { w -> async { w.key to safe<TmdbDetails?>(null) { tmdb.details(w.tmdbId, w.isTv, light = true) } } }
                .awaitAll()
                .forEach { (key, info) -> if (info != null) fresh[key] = info }
        }
        marked.map { w -> fresh[w.key]?.let { w.withDetails(it, now) } ?: w }
    }

    /** Ligne « À voir plus tard » de l'onglet Actualité, sans recharger tout l'accueil. */
    private fun wishRow(): MediaRow? {
        val today = java.time.LocalDate.now().toString()
        val items = wishlist.map { w ->
            libraryByTmdb[w.key] ?: w.toMediaItem(
                // Film pas encore sorti : la date de sortie à la place de « À VOIR ».
                w.releaseDate?.takeIf { !w.isTv && it > today }?.let { TmdbClient.shortDate(it) }?.let { "LE $it" } ?: "À VOIR",
            )
        }
        return if (items.isEmpty()) null else MediaRow("wishlist", "À voir plus tard", "ta liste d’envies", items)
    }

    private fun refreshWishRow() {
        val h = home as? HomeState.Ready ?: return
        home = h.copy(news = h.news.filterNot { it.id == "wishlist" } + listOfNotNull(wishRow()))
    }

    // ------------------------------------------------------------ Recherche

    fun onQuery(text: String) {
        query = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            val term = text.trim()
            if (term.isNotEmpty()) delay(350)
            val libraryHits = try {
                if (term.isEmpty()) {
                    client.items(sortBy = "CommunityRating", sortOrder = "Descending", limit = 21).map { SearchHit(it, defaultCaption(it)) }
                } else {
                    coroutineScope {
                        val byTitleD = async { client.items(search = term, limit = 60) }
                        val people = safe(emptyList()) { client.persons(term) }
                        val byPerson = if (people.isNotEmpty()) {
                            safe(emptyList()) { client.items(personIds = people.joinToString(",") { it.first }, limit = 60) }
                        } else {
                            emptyList()
                        }
                        val personName = people.firstOrNull()?.second
                        val hits = byTitleD.await().map { SearchHit(it, defaultCaption(it)) } +
                            byPerson.map { SearchHit(it, if (personName != null) "Avec $personName" else defaultCaption(it)) }
                        hits.distinctBy { it.item.id }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            results = libraryHits
            // Ailleurs : ce que TMDB connaît et qui n'est pas dans la bibliothèque.
            externalResults = if (term.isNotEmpty() && tmdbOn) {
                safe(emptyList()) { tmdb.search(term) }
                    .map { resolve(it) }
                    .filter { found -> libraryHits.none { it.item.id == found.id } }
                    .distinctBy { it.id }
                    .take(30)
                    .map { SearchHit(it, if (it.external) externalCaption(it) else defaultCaption(it)) }
            } else {
                emptyList()
            }
            searching = false
        }
    }

    private fun defaultCaption(item: MediaItem): String =
        listOfNotNull(item.year?.toString(), if (item.isSeries) "Série" else "Film").joinToString(" · ")

    private fun externalCaption(item: MediaItem): String =
        listOfNotNull(item.year?.toString(), if (item.isSeries) "Série" else "Film", "à découvrir").joinToString(" · ")

    // ------------------------------------------------------------ Lecture

    fun play(context: Context, item: MediaItem) {
        if (item.external) {
            openDetail(item)
            return
        }
        viewModelScope.launch {
            try {
                val target = if (item.isSeries) {
                    client.nextUp(1, seriesId = item.id).firstOrNull()
                        ?: client.firstEpisode(item.id)
                        ?: throw JellyfinException("Aucun épisode disponible pour cette série.")
                } else {
                    item
                }
                // Format que la puce vidéo de l'appareil ne sait pas décoder (souvent l'AV1) : on prévient d'abord.
                val format = DeviceCodecs.problem(safe(null) { client.videoCodec(target.id) })
                if (format != null && !externalPlayer) {
                    playWarning = PlayWarning(target, format)
                } else {
                    startPlayback(context, target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** « Lire quand même » malgré le format difficile. */
    fun playAnyway(context: Context) {
        val target = playWarning?.item ?: return
        playWarning = null
        startPlayback(context, target)
    }

    fun dismissPlayWarning() {
        playWarning = null
    }

    private fun startPlayback(context: Context, target: MediaItem) {
        // Une vidéo coupe la musique ou le livre audio.
        engine.pause()
        val url = client.streamUrl(target.id)
        val startMs = if (target.inProgress) target.positionTicks / MediaItem.TICKS_PER_MS else 0L
        val title = target.displayTitle
        val subtitle = target.episodeLine() ?: target.metaLine()
        if (externalPlayer) {
            ExternalPlayer.open(context, url, title, startMs)
        } else {
            context.startActivity(PlayerActivity.intent(context, target.id, title, subtitle, url, startMs, seriesId = target.seriesId))
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            safe(Unit) { client.setFavorite(item.id, !item.favorite) }
            detail?.let { d -> if (d.item.id == item.id) detail = d.copy(item = d.item.copy(favorite = !item.favorite)) }
            loadHome(quiet = true)
        }
    }

    // ------------------------------------------------------------ Vu / pas vu

    /** Écritures « vu » en cours : on relit le serveur une fois la dernière terminée. */
    private var playedWrites = 0
    private var playedRefresh: Job? = null

    /** Fenêtre de confirmation avant de changer d'un coup toute une série ou une saison. */
    var playedConfirm by mutableStateOf<PlayedConfirm?>(null)
        private set

    fun dismissPlayedConfirm() {
        playedConfirm = null
    }

    fun confirmPlayed() {
        val c = playedConfirm ?: return
        playedConfirm = null
        c.action()
    }

    /** Film ou série entière, depuis la fiche (une série demande confirmation). */
    fun togglePlayed(item: MediaItem, confirmed: Boolean = false) {
        if (item.external) return
        val played = !item.played
        if (item.isSeries && !confirmed) {
            val name = item.name
            playedConfirm = if (played) {
                PlayedConfirm("Tout marquer comme vu ?", "Tous les épisodes de « $name » seront marqués comme vus.", "Tout marquer vu") { togglePlayed(item, true) }
            } else {
                PlayedConfirm("Remettre la série à zéro ?", "Tous les épisodes de « $name » repasseront en « pas vu ».", "Remettre à zéro") { togglePlayed(item, true) }
            }
            return
        }
        updateDetail(item.id) { d ->
            d.copy(
                item = d.item.withPlayed(played),
                episodes = if (item.isSeries) d.episodes.map { it.withPlayed(played) } else d.episodes,
                seasons = if (item.isSeries) d.seasons.map { it.copy(played = played) } else d.seasons,
            )
        }
        patchPlayed(item.id, played)
        savePlayed(item.id, played, seriesId = item.id.takeIf { item.isSeries })
    }

    /** Un épisode de la fiche série. */
    fun toggleEpisodePlayed(ep: MediaItem) {
        val d = detail ?: return
        val played = !ep.played
        updateDetail(d.item.id) { s -> s.copy(episodes = s.episodes.map { if (it.id == ep.id) it.withPlayed(played) else it }) }
        patchPlayed(ep.id, played)
        savePlayed(ep.id, played, seriesId = d.item.id)
    }

    /** Toute une saison (« j'ai vu les saisons 1 à 3 ailleurs »), après confirmation. */
    fun toggleSeasonPlayed(seasonId: String, confirmed: Boolean = false) {
        val d = detail ?: return
        val season = d.seasons.firstOrNull { it.id == seasonId } ?: return
        val played = !season.played
        if (!confirmed) {
            playedConfirm = if (played) {
                PlayedConfirm("Marquer la saison comme vue ?", "Les épisodes de « ${season.name} » seront marqués comme vus.", "Marquer vue") { toggleSeasonPlayed(seasonId, true) }
            } else {
                PlayedConfirm("Remettre la saison à zéro ?", "Les épisodes de « ${season.name} » repasseront en « pas vu ».", "Remettre à zéro") { toggleSeasonPlayed(seasonId, true) }
            }
            return
        }
        updateDetail(d.item.id) { s ->
            s.copy(
                seasons = s.seasons.map { if (it.id == seasonId) it.copy(played = played) else it },
                episodes = if (s.seasonId == seasonId) s.episodes.map { it.withPlayed(played) } else s.episodes,
            )
        }
        savePlayed(seasonId, played, seriesId = d.item.id)
    }

    private fun savePlayed(itemId: String, played: Boolean, seriesId: String?) {
        playedWrites++
        viewModelScope.launch {
            try {
                client.setPlayed(itemId, played)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.toast("Pas enregistré sur le serveur : ${e.friendly()}")
            } finally {
                playedWrites--
            }
            if (playedWrites > 0) return@launch
            // Ce que dit le serveur : prochain épisode, saisons vues, « Reprendre la lecture »…
            playedRefresh?.cancel()
            playedRefresh = viewModelScope.launch {
                if (seriesId != null) refreshSeries(seriesId) else refreshDetailItem(itemId)
                loadHome(quiet = true)
            }
        }
    }

    private suspend fun refreshDetailItem(itemId: String) {
        val fresh = safe<MediaItem?>(null) { client.item(itemId) } ?: return
        updateDetail(itemId) { d -> d.copy(item = d.item.copy(played = fresh.played, positionTicks = fresh.positionTicks, playedPercentage = fresh.playedPercentage)) }
    }

    private suspend fun refreshSeries(seriesId: String): Unit = coroutineScope {
        val seasonId = detail?.takeIf { it.item.id == seriesId }?.seasonId
        val seasonsD = async { safe<List<SeasonInfo>?>(null) { client.seasons(seriesId) } }
        val nextD = async { safe<List<MediaItem>?>(null) { client.nextUp(1, seriesId = seriesId) } }
        val seriesD = async { safe<MediaItem?>(null) { client.item(seriesId) } }
        val episodesD = async { seasonId?.let { sid -> safe<List<MediaItem>?>(null) { client.episodes(seriesId, sid) } } }
        val seasons = seasonsD.await()
        val next = nextD.await()
        val series = seriesD.await()
        val episodes = episodesD.await()
        updateDetail(seriesId) { d ->
            d.copy(
                item = series?.let { d.item.copy(played = it.played) } ?: d.item,
                seasons = seasons ?: d.seasons,
                nextUp = if (next != null) next.firstOrNull() else d.nextUp,
                episodes = if (episodes != null && d.seasonId == seasonId) episodes else d.episodes,
            )
        }
        series?.let { patchPlayed(seriesId, it.played) }
    }

    /** Les affiches déjà affichées (Films, Séries, recherche, retour de fiche) prennent le nouvel état. */
    private fun patchPlayed(id: String, played: Boolean) {
        fun List<MediaItem>.patch() = map { if (it.id == id && it.played != played) it.withPlayed(played) else it }
        fun List<MediaRow>.patchRows() = map { r -> if (r.items.any { it.id == id }) r.copy(items = r.items.patch()) else r }
        browse?.let { b -> browse = b.copy(rows = b.rows.patchRows()) }
        (home as? HomeState.Ready)?.let { h -> home = h.copy(rows = h.rows.patchRows(), news = h.news.patchRows()) }
        results = results.map { hit -> if (hit.item.id == id) hit.copy(item = hit.item.withPlayed(played)) else hit }
        detailStack.replaceAll { if (it.id == id) it.withPlayed(played) else it }
        detail?.let { d ->
            detail = d.copy(collection = d.collection.patch(), similar = d.similar.patch())
        }
    }

    private suspend fun <T> safe(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }
}

fun Throwable.friendly(): String = when (this) {
    is JellyfinException -> message ?: "Erreur du serveur."
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        "Impossible de joindre le serveur. Hors de la maison, vérifie que Tailscale est allumé."
    is SocketTimeoutException -> "Le serveur met trop de temps à répondre."
    is IllegalArgumentException -> "Adresse du serveur invalide."
    else -> message ?: "Erreur inattendue."
}
