package fr.nosflix.app.ui.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.audio.AudioKind
import fr.nosflix.app.data.AudioAlbum
import fr.nosflix.app.data.AudioPlaylist
import fr.nosflix.app.data.AudioTrack
import fr.nosflix.app.data.CollectionKind
import fr.nosflix.app.data.CollectionState
import fr.nosflix.app.data.MusicHomeState
import fr.nosflix.app.data.MusicStyle
import fr.nosflix.app.data.plural
import fr.nosflix.app.ui.CenterLoader
import fr.nosflix.app.ui.ErrorBox
import fr.nosflix.app.ui.LocalClient
import fr.nosflix.app.ui.LocalDims
import fr.nosflix.app.ui.LocalIsTv
import fr.nosflix.app.ui.MinimalScroll
import fr.nosflix.app.ui.NfButton
import fr.nosflix.app.ui.NfChip
import fr.nosflix.app.ui.NfSearchField
import fr.nosflix.app.ui.NfColors
import fr.nosflix.app.ui.NfEasing
import fr.nosflix.app.ui.NfIcons
import fr.nosflix.app.ui.NfMotion
import fr.nosflix.app.ui.TvTopTabs
import fr.nosflix.app.ui.downLeavesField
import fr.nosflix.app.ui.nfFieldColors
import fr.nosflix.app.ui.searchKeyboardActions
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

/** URL de l'image « Primary » d'un élément Jellyfin, carrée. */
@Composable
fun coverUrl(itemId: String?, size: Int = 360): String? =
    LocalClient.current.imageUrl(itemId, "Primary", fillWidth = size, fillHeight = size)

// ---------------------------------------------------------------- Accueil Musique

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicHomeContent(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val lib = vm.audio
    val (savedIndex, savedOffset) = lib.musicScroll
    val gridState = rememberLazyGridState(savedIndex, savedOffset)
    DisposableEffect(Unit) {
        onDispose { lib.musicScroll = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
    }
    val restore = remember { FocusRequester() }
    val restoreKey = lib.musicFocusKey
    val onOpen: (String) -> Unit = { key -> lib.musicFocusKey = key }

    val state = lib.music
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 128.dp else 104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = d.rowPad, end = d.rowPad, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isTv) fullItem("tabs") { TvTopTabs(vm, startPad = 0.dp) }
            fullItem("header") {
                SectionHeader(
                    kicker = "MUSIQUE",
                    title = "Ta musique",
                    sub = (state as? MusicHomeState.Ready)?.let { s ->
                        listOf(plural(s.all.size, "album", "albums"), plural(s.trackCount, "titre", "titres"), plural(s.playlists.size, "playlist", "playlists")).joinToString(" · ")
                    }.orEmpty(),
                )
            }
            fullItem("search") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NfSearchField(
                        value = lib.query,
                        onValueChange = { lib.onQuery(it) },
                        placeholder = "Album, artiste, titre…",
                        modifier = Modifier.weight(1f),
                    )
                    NfButton(
                        if (isTv) "Lecture aléatoire" else "Aléatoire",
                        onClick = { lib.shuffleAll { tracks, label -> vm.engine.playTracks(tracks, shuffled = true, sourceLabel = label) } },
                        icon = AudioIcons.Shuffle,
                    )
                }
            }
            val style = lib.style
            if (state is MusicHomeState.Ready && state.styles.size > 1) fullItem("styles") {
                StyleChips(state.styles.map { it.first }, style, onPick = { lib.pickStyle(it) })
            }
            val search = lib.search
            when {
                search != null -> {
                    // Le style choisi filtre aussi la recherche (les morceaux d'après leur album).
                    val byId = (state as? MusicHomeState.Ready)?.all?.associateBy { it.id }.orEmpty()
                    val albums = if (style == null) search.albums else search.albums.filter { style in it.styles }
                    val tracks = if (style == null) search.tracks else search.tracks.filter { t -> byId[t.albumId]?.let { style in it.styles } ?: true }
                    searchResults(vm, search.loading, albums, search.playlists, tracks, onOpen, style)
                }
                state is MusicHomeState.Loading -> fullItem("loading") { CenterLoader(Modifier.fillMaxWidth().height(220.dp)) }
                state is MusicHomeState.Error -> fullItem("error") { ErrorBox(state.message, onRetry = { lib.loadMusic() }) }
                state is MusicHomeState.Ready && style != null -> {
                    val list = lib.albumsOf(state.all)
                    fullItem("style-title") {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Text(style.label, color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Spacer(Modifier.width(10.dp))
                            Text(plural(list.size, "album", "albums"), color = NfColors.Muted, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    gridItems(list, key = { "all-" + it.id }) { a ->
                        val key = "all:" + a.id
                        AlbumTile(a, width = null, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openAlbum(a) }
                    }
                }
                state is MusicHomeState.Ready -> {
                    if (state.all.isEmpty()) {
                        fullItem("empty") {
                            Text(
                                "Aucun album pour l’instant. Ajoute une bibliothèque « Musique » dans Jellyfin, ou attends la fin de l’analyse du serveur.",
                                color = NfColors.Muted,
                                fontSize = 15.sp,
                            )
                        }
                    }
                    fullItem("quick") {
                        QuickRow(vm, state.recent, restoreKey, restore, onOpen)
                    }
                    fullItem("playlists") {
                        TileRow("Tes playlists", "") {
                            listItems(state.playlists, key = { "pl-" + it.id }) { pl ->
                                val key = "pl:" + pl.id
                                PlaylistTile(pl, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openPlaylist(pl) }
                            }
                            item(key = "new") { NewPlaylistTile { lib.askAddToPlaylist(emptyList()) } }
                        }
                    }
                    if (state.added.isNotEmpty()) fullItem("added") {
                        TileRow("Ajoutés récemment", "") {
                            listItems(state.added, key = { "add-" + it.id }) { a ->
                                val key = "added:" + a.id
                                AlbumTile(a, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openAlbum(a) }
                            }
                        }
                    }
                    if (state.discover.isNotEmpty()) fullItem("discover") {
                        TileRow("À découvrir", "des albums pris au hasard") {
                            listItems(state.discover, key = { "disc-" + it.id }) { a ->
                                val key = "disc:" + a.id
                                AlbumTile(a, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openAlbum(a) }
                            }
                        }
                    }
                    if (state.all.isNotEmpty()) fullItem("all-title") {
                        Text("Tous les albums", color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    gridItems(state.all, key = { "all-" + it.id }) { a ->
                        val key = "all:" + a.id
                        AlbumTile(a, width = null, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openAlbum(a) }
                    }
                }
            }
        }
    }
    // TV : on revient sur la vignette ouverte avant (ou la première).
    if (isTv && state is MusicHomeState.Ready) {
        LaunchedEffect(Unit) {
            delay(200)
            if (restoreKey != null) runCatching { restore.requestFocus() }
        }
    }
}

private fun LazyGridScope.fullItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.searchResults(
    vm: AppViewModel,
    loading: Boolean,
    albums: List<AudioAlbum>,
    playlists: List<AudioPlaylist>,
    tracks: List<AudioTrack>,
    onOpen: (String) -> Unit,
    style: MusicStyle?,
) {
    if (loading && albums.isEmpty() && tracks.isEmpty()) {
        fullItem("s-loading") { CenterLoader(Modifier.fillMaxWidth().height(160.dp)) }
        return
    }
    if (!loading && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()) {
        fullItem("s-none") {
            Text(
                if (style == null) "Rien ne correspond. Essaie un autre mot." else "Rien en « ${style.label} » pour cette recherche. Choisis « Tous » pour chercher partout.",
                color = NfColors.Muted,
                fontSize = 14.sp,
            )
        }
        return
    }
    if (playlists.isNotEmpty()) fullItem("s-pl") {
        TileRow("Playlists", "") {
            listItems(playlists, key = { "spl-" + it.id }) { pl -> PlaylistTile(pl) { onOpen("pl:" + pl.id); vm.openPlaylist(pl) } }
        }
    }
    if (albums.isNotEmpty()) fullItem("s-albums") {
        TileRow("Albums", "") {
            listItems(albums, key = { "sa-" + it.id }) { a -> AlbumTile(a) { onOpen("all:" + a.id); vm.openAlbum(a) } }
        }
    }
    if (tracks.isNotEmpty()) {
        fullItem("s-tracks-title") { Text("Titres", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        tracks.forEachIndexed { i, t ->
            item(key = "st-$i-" + t.id, span = { GridItemSpan(maxLineSpan) }) {
                TrackRow(vm, t, number = null, showCover = true, onClick = { vm.engine.playTracks(tracks, i, sourceLabel = "Recherche") }, kind = null)
            }
        }
    }
}

/**
 * Pastilles de styles sous la recherche : « Tous », « Jazz », « Rock »…
 * TV : en arrivant d'en haut ou d'en bas, le focus va sur la pastille choisie.
 */
@Composable
private fun StyleChips(styles: List<MusicStyle>, selected: MusicStyle?, onPick: (MusicStyle?) -> Unit) {
    val selectedIndex = if (selected == null) 0 else styles.indexOf(selected) + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, selectedIndex - 1))
    val current = remember { FocusRequester() }
    LazyRow(
        state = listState,
        modifier = Modifier
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) {
                        runCatching { current.requestFocus() }
                    }
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        item(key = "all") {
            Box(if (selected == null) Modifier.focusRequester(current) else Modifier) { NfChip("Tous", selected == null) { onPick(null) } }
        }
        listItems(styles, key = { it.name }) { s ->
            Box(if (s == selected) Modifier.focusRequester(current) else Modifier) { NfChip(s.label, s == selected) { onPick(s) } }
        }
    }
}

@Composable
private fun SectionHeader(kicker: String, title: String, sub: String) {
    val isTv = LocalIsTv.current
    Column(Modifier.padding(top = if (isTv) 4.dp else 16.dp)) {
        Text(kicker, color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(title, color = Color.White, fontSize = LocalDims.current.titleSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (sub.isNotEmpty()) Text(sub, color = NfColors.Muted, fontSize = 14.sp)
    }
}

/** Raccourcis façon Spotify : titres likés + albums écoutés récemment. */
@Composable
private fun QuickRow(vm: AppViewModel, recent: List<AudioAlbum>, restoreKey: String?, restore: FocusRequester, onOpen: (String) -> Unit) {
    val isTv = LocalIsTv.current
    if (isTv) {
        TileRow("Écoutés récemment", if (recent.isEmpty()) "rien pour l’instant" else "") {
            item(key = "liked") { LikedTile(focus = if (restoreKey == "liked") restore else null) { onOpen("liked"); vm.openLiked() } }
            listItems(recent, key = { "rec-" + it.id }) { a ->
                val key = "recent:" + a.id
                AlbumTile(a, focus = if (key == restoreKey) restore else null) { onOpen(key); vm.openAlbum(a) }
            }
        }
        return
    }
    // Téléphone : deux colonnes de raccourcis compacts.
    val tiles: List<Pair<String, @Composable (Modifier) -> Unit>> = buildList {
        add("liked" to { m -> QuickTile(null, "Titres likés", m, liked = true) { onOpen("liked"); vm.openLiked() } })
        recent.take(7).forEach { a ->
            add("recent:" + a.id to { m -> QuickTile(coverUrl(a.imageItemId, 160), a.title, m) { onOpen("recent:" + a.id); vm.openAlbum(a) } })
        }
    }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (_, tile) -> tile(Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickTile(url: String?, title: String, modifier: Modifier, liked: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (liked) {
            LikedArt(Modifier.size(52.dp), corner = 0.dp)
        } else {
            AudioCover(url, title, Modifier.size(52.dp), corner = 0.dp, showText = false)
        }
        Text(
            title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/** Image des titres likés : cœur blanc sur dégradé rouge. */
@Composable
private fun LikedArt(modifier: Modifier, corner: Dp = 6.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFFFF4D57), Color(0xFF6E0A12)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AudioIcons.HeartFilled, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

/** Une ligne de vignettes avec son titre. */
@Composable
private fun TileRow(title: String, sub: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val d = LocalDims.current
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(sub, color = NfColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 16.dp else 12.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------- Vignettes

@Composable
private fun rememberLift(interaction: MutableInteractionSource): Float {
    val focused by interaction.collectIsFocusedAsState()
    val lift by animateFloatAsState(if (focused) 1.07f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "tile-lift")
    return lift
}

@Composable
private fun Tile(
    width: Dp?,
    title: String,
    sub: String,
    focus: FocusRequester?,
    onClick: () -> Unit,
    art: @Composable (Modifier) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = rememberLift(interaction)
    val tileWidth = width ?: Dp.Unspecified
    Column(
        (if (width != null) Modifier.width(tileWidth) else Modifier.fillMaxWidth())
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        art(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { scaleX = lift; scaleY = lift },
        )
        Text(title, color = if (focused) Color.White else Color(0xFFE4E4E9), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, color = NfColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val TileW: Dp @Composable get() = if (LocalIsTv.current) 128.dp else 140.dp

@Composable
fun AlbumTile(album: AudioAlbum, width: Dp? = TileW, focus: FocusRequester? = null, onClick: () -> Unit) {
    val url = coverUrl(album.imageItemId)
    Tile(width, album.title, album.artist, focus, onClick) { m -> AudioCover(url, album.title, m, subtitle = album.artist) }
}

@Composable
private fun PlaylistTile(playlist: AudioPlaylist, focus: FocusRequester? = null, onClick: () -> Unit) {
    val url = coverUrl(playlist.imageItemId)
    val sub = playlist.trackCount?.let { plural(it, "titre", "titres") } ?: "Playlist"
    Tile(TileW, playlist.name, sub, focus, onClick) { m -> AudioCover(url, playlist.name, m, subtitle = "Playlist") }
}

@Composable
private fun LikedTile(focus: FocusRequester?, onClick: () -> Unit) {
    Tile(TileW, "Titres likés", "Playlist", focus, onClick) { m -> LikedArt(m) }
}

@Composable
private fun NewPlaylistTile(onClick: () -> Unit) {
    Tile(TileW, "Nouvelle playlist", "", null, onClick) { m ->
        Box(
            m
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NfIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
    }
}

// ---------------------------------------------------------------- Album, playlist, titres likés

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionContent(vm: AppViewModel) {
    val c = vm.audio.collection ?: return
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val engine = vm.engine
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val url = coverUrl(c.imageItemId, 600)
    val color = rememberCoverColor(url, c.title)
    val label = when (c.kind) {
        CollectionKind.Album -> "Album · ${c.title}"
        CollectionKind.Playlist -> "Playlist · ${c.title}"
        CollectionKind.Liked -> "Titres likés"
    }
    val isTvScreen = LocalIsTv.current
    val playAll: (Int, Boolean) -> Unit = { start, shuffled ->
        engine.playTracks(c.tracks, start, shuffled, label)
        // TV : on passe directement sur le lecteur plein écran.
        if (isTvScreen) vm.showNowPlaying = true
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to lerpToBlack(color, 0.15f), 0.55f to Color.Black)),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "header") { CollectionHeader(vm, c, url, playAll, playFocus) }
            when {
                c.loading -> item(key = "loading") { CenterLoader(Modifier.fillMaxWidth().height(200.dp)) }
                c.error != null -> item(key = "error") { ErrorBox(c.error, onRetry = { vm.audio.reloadCollection() }) }
                c.tracks.isEmpty() -> item(key = "empty") {
                    Text(
                        if (c.kind == CollectionKind.Playlist) "Cette playlist est vide. Ajoute des titres avec le bouton ⋮ à côté d’un morceau." else "Aucun titre ici pour l’instant.",
                        color = NfColors.Muted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(d.rowPad),
                    )
                }
                else -> itemsIndexed(c.tracks, key = { i, t -> "$i-" + t.id }) { i, t ->
                    Box(Modifier.padding(horizontal = d.rowPad - 8.dp)) {
                        TrackRow(
                            vm = vm,
                            track = t,
                            number = if (c.kind == CollectionKind.Album) (t.index ?: (i + 1)) else null,
                            showCover = c.kind != CollectionKind.Album,
                            onClick = { playAll(i, false) },
                            kind = c.kind,
                        )
                    }
                }
            }
        }
    }
    if (isTv && !c.loading) {
        LaunchedEffect(c.id) {
            delay(150)
            runCatching { playFocus.requestFocus() }
        }
    }
}

@Composable
private fun CollectionHeader(vm: AppViewModel, c: CollectionState, url: String?, playAll: (Int, Boolean) -> Unit, playFocus: FocusRequester) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val totalMs = c.tracks.sumOf { it.durationMs }
    val meta = listOfNotNull(
        when (c.kind) {
            CollectionKind.Album -> "Album"
            CollectionKind.Playlist -> "Playlist"
            CollectionKind.Liked -> "Playlist"
        },
        if (c.tracks.isNotEmpty()) plural(c.tracks.size, "titre", "titres") + (if (totalMs > 0) ", " + longDuration(totalMs) else "") else null,
    ).joinToString(" · ")
    // Le gros bouton devient « Pause » quand c'est cet album / cette playlist qui joue.
    val engine = vm.engine
    val thisOne = engine.kind == AudioKind.Music && c.tracks.any { it.id == engine.current?.id }
    val art: @Composable (Modifier) -> Unit = { m ->
        if (c.kind == CollectionKind.Liked) LikedArt(m) else AudioCover(url, c.title, m, subtitle = c.subtitle)
    }
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlayCircle(
                onClick = { if (thisOne) engine.toggle() else playAll(0, false) },
                focus = playFocus,
                enabled = c.tracks.isNotEmpty(),
                playing = thisOne && engine.playing,
            )
            RoundIconButton(AudioIcons.Shuffle, "Lecture aléatoire", { playAll(0, true) }, size = 46.dp)
            RoundIconButton(AudioIcons.PlaylistAdd, "Ajouter à une playlist", { if (c.tracks.isNotEmpty()) vm.audio.askAddToPlaylist(c.tracks) }, size = 46.dp)
        }
    }
    val back: @Composable () -> Unit = {
        if (!isTv) {
            Box(
                Modifier
                    .padding(start = 4.dp, top = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { vm.back() },
                contentAlignment = Alignment.Center,
            ) { Icon(NfIcons.Back, contentDescription = "Retour", tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
    if (isTv) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = d.rowPad, end = d.rowPad, top = 28.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            art(Modifier.size(190.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(meta.uppercase(), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 40.sp)
                if (c.kind == CollectionKind.Album && c.subtitle.isNotBlank()) Text(c.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                actions()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            back()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.rowPad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                art(Modifier.size(220.dp))
                Spacer(Modifier.height(18.dp))
            }
            Column(Modifier.padding(horizontal = d.rowPad), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c.kind == CollectionKind.Album && c.subtitle.isNotBlank()) Text(c.subtitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(meta, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                actions()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Gros bouton rouge « Lecture ». */
@Composable
fun PlayCircle(onClick: () -> Unit, focus: FocusRequester? = null, enabled: Boolean = true, size: Dp = 56.dp, playing: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = rememberLift(interaction)
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .size(size)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(CircleShape)
            .background(if (focused) Color.White else if (enabled) NfColors.Accent else NfColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) NfIcons.Pause else NfIcons.Play,
            contentDescription = if (playing) "Pause" else "Lecture",
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

// ---------------------------------------------------------------- Ligne d'un morceau

@Composable
fun TrackRow(
    vm: AppViewModel,
    track: AudioTrack,
    number: Int?,
    showCover: Boolean,
    onClick: () -> Unit,
    kind: CollectionKind?,
) {
    val engine = vm.engine
    val isCurrent = engine.kind == AudioKind.Music && engine.current?.id == track.id
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCover) {
            Box(contentAlignment = Alignment.Center) {
                AudioCover(coverUrl(track.imageItemId, 120), track.album.ifBlank { track.title }, Modifier.size(44.dp), corner = 4.dp, showText = false)
                if (isCurrent) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        EqualizerBars(engine.playing)
                    }
                }
            }
        } else {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    EqualizerBars(engine.playing)
                } else {
                    Text((number ?: 0).toString(), color = NfColors.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) NfColors.AccentText else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (showCover) listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ") else track.artist
            if (sub.isNotBlank()) Text(sub, color = NfColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vm.isFavorite(track)) Icon(AudioIcons.HeartFilled, contentDescription = "Liké", tint = NfColors.AccentText, modifier = Modifier.size(16.dp))
        if (track.durationMs > 0) Text(clock(track.durationMs), color = NfColors.Muted, fontSize = 13.sp)
        Box {
            RoundIconButton(AudioIcons.More, "Plus d’options", { menu = true }, size = 40.dp, iconSize = 20.dp, tint = NfColors.Muted)
            TrackMenu(vm, track, kind, expanded = menu, onDismiss = { menu = false })
        }
    }
}

@Composable
private fun TrackMenu(vm: AppViewModel, track: AudioTrack, kind: CollectionKind?, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = NfColors.Surface2,
    ) {
        val colors = MenuDefaults.itemColors(textColor = Color.White, leadingIconColor = Color.White)
        fun act(block: () -> Unit): () -> Unit = { onDismiss(); block() }
        DropdownMenuItem(
            text = { Text("Lire ensuite") },
            leadingIcon = { Icon(AudioIcons.PlayNext, null, Modifier.size(20.dp)) },
            onClick = act { vm.engine.playNext(listOf(track)); vm.audio.toast("Sera lu ensuite") },
            colors = colors,
        )
        DropdownMenuItem(
            text = { Text("Ajouter à la file d’attente") },
            leadingIcon = { Icon(AudioIcons.Queue, null, Modifier.size(20.dp)) },
            onClick = act { vm.engine.enqueue(listOf(track)); vm.audio.toast("Ajouté à la file d’attente") },
            colors = colors,
        )
        DropdownMenuItem(
            text = { Text("Ajouter à une playlist…") },
            leadingIcon = { Icon(AudioIcons.PlaylistAdd, null, Modifier.size(20.dp)) },
            onClick = act { vm.audio.askAddToPlaylist(listOf(track)) },
            colors = colors,
        )
        val liked = vm.isFavorite(track)
        DropdownMenuItem(
            text = { Text(if (liked) "Retirer des titres likés" else "J’aime") },
            leadingIcon = { Icon(if (liked) AudioIcons.HeartFilled else AudioIcons.Heart, null, Modifier.size(20.dp)) },
            onClick = act { vm.toggleTrackFavorite(track) },
            colors = colors,
        )
        if (kind == CollectionKind.Playlist && track.playlistEntryId != null) {
            DropdownMenuItem(
                text = { Text("Retirer de cette playlist") },
                leadingIcon = { Icon(AudioIcons.Minus, null, Modifier.size(20.dp)) },
                onClick = act { vm.audio.removeFromPlaylist(track) },
                colors = colors,
            )
        }
        if (kind != CollectionKind.Album && track.albumId != null) {
            DropdownMenuItem(
                text = { Text("Voir l’album") },
                leadingIcon = { Icon(AudioIcons.Album, null, Modifier.size(20.dp)) },
                onClick = act { vm.openAlbumOf(track) },
                colors = colors,
            )
        }
    }
}

// ---------------------------------------------------------------- Ajouter à une playlist

@Composable
fun AddToPlaylistDialog(vm: AppViewModel) {
    val tracks = vm.audio.addingTracks ?: return
    var creating by remember { mutableStateOf(tracks.isEmpty()) }
    var name by remember { mutableStateOf("") }
    val close = { vm.audio.addingTracks = null }
    AlertDialog(
        onDismissRequest = close,
        containerColor = NfColors.Surface,
        title = {
            Text(
                when {
                    tracks.isEmpty() -> "Nouvelle playlist"
                    tracks.size == 1 -> "Ajouter « ${tracks[0].title} »"
                    else -> "Ajouter ${tracks.size} titres"
                },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Nom de la playlist") },
                        singleLine = true,
                        colors = nfFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PlaylistChoice(null, "Nouvelle playlist…") { creating = true }
                    LazyColumn(Modifier.height(280.dp)) {
                        listItems(vm.audio.playlists, key = { it.id }) { pl ->
                            PlaylistChoice(pl, pl.name) {
                                close()
                                vm.audio.addToPlaylist(pl, tracks)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                NfButton("Créer", onClick = {
                    if (name.isNotBlank()) {
                        close()
                        vm.audio.createPlaylist(name, tracks)
                    }
                }, primary = true, enabled = name.isNotBlank())
            }
        },
        dismissButton = { NfButton("Annuler", onClick = close) },
    )
}

@Composable
private fun PlaylistChoice(playlist: AudioPlaylist?, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (playlist == null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(NfIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        } else {
            AudioCover(coverUrl(playlist.imageItemId, 120), playlist.name, Modifier.size(40.dp), corner = 4.dp, showText = false)
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            playlist?.trackCount?.let { Text(plural(it, "titre", "titres"), color = NfColors.Muted, fontSize = 12.sp) }
        }
    }
}
