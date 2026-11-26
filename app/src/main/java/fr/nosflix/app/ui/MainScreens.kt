package fr.nosflix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.os.SystemClock
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.navigationBars
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.audio.AudioKind
import fr.nosflix.app.data.PlayWarning
import fr.nosflix.app.data.ServerRoute
import fr.nosflix.app.data.PlayedConfirm
import fr.nosflix.app.player.AudioPref
import fr.nosflix.app.player.DeviceCodecs
import fr.nosflix.app.player.LanguagePrefs
import fr.nosflix.app.player.SubtitlePref
import fr.nosflix.app.update.UpdateInfo
import fr.nosflix.app.update.Updater
import fr.nosflix.app.ui.audio.AddToPlaylistDialog
import fr.nosflix.app.ui.audio.BookContent
import fr.nosflix.app.ui.audio.BooksHomeContent
import fr.nosflix.app.ui.audio.CollectionContent
import fr.nosflix.app.ui.audio.MiniPlayer
import fr.nosflix.app.ui.audio.NowPlayingPill
import fr.nosflix.app.ui.audio.MusicHomeContent
import fr.nosflix.app.ui.audio.NowPlayingScreen
import fr.nosflix.app.ui.audio.coverUrl
import fr.nosflix.app.data.BrowseKey
import fr.nosflix.app.data.BrowseState
import fr.nosflix.app.data.HomeState
import fr.nosflix.app.data.JellyfinClient
import fr.nosflix.app.data.MainScreen
import fr.nosflix.app.data.SearchHit
import fr.nosflix.app.data.SortOption
import fr.nosflix.app.data.plural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

internal val RailCollapsed = 64.dp
private val RailExpanded = 220.dp

// ---------------------------------------------------------------- Racine

@Composable
fun NosFlixRoot(vm: AppViewModel, showIntro: Boolean) {
    CompositionLocalProvider(LocalFormatBadges provides vm.formatBadges) {
        RootContent(vm, showIntro)
    }
}

@Composable
private fun RootContent(vm: AppViewModel, showIntro: Boolean) {
    var introDone by rememberSaveable { mutableStateOf(!showIntro) }
    Box(
        Modifier
            .fillMaxSize()
            .background(NfColors.Black),
    ) {
        val isTv = LocalIsTv.current
        val nowPlaying = introDone && vm.session != null && vm.showNowPlaying
        when {
            !introDone -> IntroScreen(onDone = { introDone = true })
            vm.session == null -> LoginScreen(vm)
            vm.choosingProfile -> ProfilesScreen(vm)
            // TV : le lecteur prend tout l'écran (la télécommande ne doit pas filer derrière).
            isTv && nowPlaying -> NowPlayingScreen(vm)
            isTv -> MainTv(vm)
            else -> MainPhone(vm)
        }
        if (!isTv && nowPlaying) NowPlayingScreen(vm)
        if (introDone && vm.showSettings && vm.session != null) SettingsDialog(vm)
        if (introDone && vm.session != null) AddToPlaylistDialog(vm)
        vm.playWarning?.let { PlayWarningDialog(vm, it) }
        if (vm.askExit) ExitDialog(vm)
        vm.playedConfirm?.let { PlayedConfirmDialog(vm, it) }
        if (introDone && vm.session != null) vm.update?.let { UpdateDialog(vm, it) }
        AudioToast(vm.audio.message, Modifier.align(if (isTv) Alignment.TopCenter else Alignment.BottomCenter), isTv)
    }
}

/** « Mise à jour disponible » : nouvelle version déposée sur le serveur de la maison. */
@Composable
private fun UpdateDialog(vm: AppViewModel, info: UpdateInfo) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val progress = vm.updateProgress
    AlertDialog(
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        containerColor = NfColors.Surface,
        title = { Text("Mise à jour disponible", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "NosFlix ${info.versionName}" + (if (info.date.isNotBlank()) " du ${info.date}" else "") +
                        " (tu as la ${Updater.currentName} du ${Updater.currentDate}).",
                    color = NfColors.TextSoft,
                    fontSize = 14.sp,
                )
                info.notes.take(8).forEach { note ->
                    Text("•  $note", color = Color.White, fontSize = 14.sp)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = NfColors.Accent,
                        trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    Text("Téléchargement… ${(progress * 100).toInt()} %", color = NfColors.Muted, fontSize = 12.sp)
                } else {
                    Text(
                        "Android va demander de confirmer. La première fois, il faut autoriser NosFlix à installer des applis. " +
                            "L’appli se ferme pendant la mise à jour : relance-la ensuite.",
                        color = NfColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            NfButton(
                if (progress != null) "Téléchargement…" else "Mettre à jour",
                onClick = { vm.installUpdate(context) },
                primary = true,
                enabled = progress == null,
                focusRequester = focus,
            )
        },
        dismissButton = { if (progress == null) NfButton("Plus tard", onClick = { vm.dismissUpdate() }) },
    )
    // La fenêtre peut s'ouvrir pendant le chargement de l'accueil : on insiste un peu pour que
    // la télécommande soit bien sur « Mettre à jour ».
    LaunchedEffect(Unit) {
        for (wait in listOf(120L, 250L, 500L, 900L)) {
            delay(wait)
            runCatching { focus.requestFocus() }
        }
    }
}

/** Avant de lancer une vidéo dans un format que la puce de l'appareil ne décode pas. */
@Composable
private fun PlayWarningDialog(vm: AppViewModel, warning: PlayWarning) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.dismissPlayWarning() },
        containerColor = NfColors.Surface,
        title = { Text("Format difficile pour cet appareil", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "« ${warning.item.displayTitle} » est encodé en ${warning.format}. La puce vidéo de cet appareil " +
                    "ne sait pas décoder ce format : la lecture risque d’être saccadée, ou de ne pas démarrer.\n\n" +
                    "Il passera sans souci sur un appareil plus récent (ordinateur, téléphone récent).",
                color = NfColors.TextSoft,
                fontSize = 15.sp,
            )
        },
        confirmButton = { NfButton("Lire quand même", onClick = { vm.playAnyway(context) }) },
        dismissButton = { NfButton("Annuler", onClick = { vm.dismissPlayWarning() }, primary = true, focusRequester = focus) },
    )
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
}

/** Petit message passager en bas de l'écran. */
@Composable
private fun AudioToast(message: String?, modifier: Modifier, isTv: Boolean) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(220)),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = if (isTv) 24.dp else 0.dp, bottom = if (isTv) 0.dp else 96.dp, start = 24.dp, end = 24.dp),
    ) {
        // Pendant la disparition, on garde le dernier texte affiché.
        var last by remember { mutableStateOf("") }
        LaunchedEffect(message) { if (message != null) last = message }
        Text(
            message ?: last,
            color = Color.Black,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun isMusic(vm: AppViewModel): Boolean =
    vm.screen == MainScreen.Music || (vm.screen == MainScreen.MusicCollection)

private fun isBooks(vm: AppViewModel): Boolean =
    vm.screen == MainScreen.Books || vm.screen == MainScreen.Book

@Composable
private fun ScreenContent(vm: AppViewModel) {
    when (vm.screen) {
        MainScreen.Home -> HomeContent(vm)
        MainScreen.Browse -> BrowseContent(vm)
        MainScreen.Search -> SearchContent(vm)
        MainScreen.Detail -> DetailContent(vm)
        MainScreen.Music -> MusicHomeContent(vm)
        MainScreen.MusicCollection -> CollectionContent(vm)
        MainScreen.Books -> BooksHomeContent(vm)
        MainScreen.Book -> BookContent(vm)
        MainScreen.News -> NewsContent(vm)
    }
}

private fun isBrowsing(vm: AppViewModel, key: BrowseKey): Boolean =
    vm.screen == MainScreen.Browse && vm.browse?.key == key

/** Section Films ouverte : la page Films ou un genre de films. */
private fun isFilms(vm: AppViewModel): Boolean {
    if (vm.screen != MainScreen.Browse) return false
    val key = vm.browse?.key
    return key == BrowseKey.Movies || (key is BrowseKey.Genre && key.scope == "Movie") || (key is BrowseKey.Section && key.scope == "Movie")
}

/** Section Séries ouverte : la page Séries ou un de ses « Voir tout ». */
private fun isShows(vm: AppViewModel): Boolean {
    if (vm.screen != MainScreen.Browse) return false
    val key = vm.browse?.key
    return key == BrowseKey.Shows || (key is BrowseKey.Genre && key.scope == "Series") || (key is BrowseKey.Section && key.scope == "Series")
}

// ---------------------------------------------------------------- TV

@Composable
private fun MainTv(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != MainScreen.Home) { vm.back() }
    // Retour sur l'accueil : on demande avant de quitter l'appli.
    BackHandler(enabled = vm.screen == MainScreen.Home) { vm.askExit = true }

    // Changement de catégorie : l'élément sélectionné disparaît et Android redonnait la main au
    // menu de gauche, qui s'ouvrait. On ramène le focus sur l'onglet de la catégorie (ou dans l'écran),
    // sauf si l'écran l'a déjà placé lui-même ou si on s'est remis à naviguer entre-temps.
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    var lastLeftAt by remember { mutableLongStateOf(0L) }
    var picks by remember { mutableIntStateOf(0) }
    val focusContent: () -> Unit = {
        if (runCatching { tabFocus.requestFocus() }.isFailure) runCatching { contentFocus.requestFocus() }
    }
    LaunchedEffect(vm.screen, vm.browse?.key, picks) {
        val changedAt = SystemClock.uptimeMillis()
        repeat(12) {
            delay(250)
            if (contentHasFocus || lastKeyAt > changedAt) return@LaunchedEffect
            focusContent()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NfColors.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    lastKeyAt = SystemClock.uptimeMillis()
                    if (e.key == Key.DirectionLeft) lastLeftAt = lastKeyAt
                }
                false
            },
    ) {
        CompositionLocalProvider(LocalTabFocus provides tabFocus) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = RailCollapsed)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRequester(contentFocus)
                    .focusGroup(),
            ) {
                ScreenContent(vm)
            }
        }
        // Un choix dans le menu le referme : le focus repart dans l'écran.
        SideRail(
            vm,
            onPicked = { picks++ },
            enteredWithLeft = { SystemClock.uptimeMillis() - lastLeftAt < 600 },
            giveBack = focusContent,
        )
    }
}

/** Onglet du haut de la catégorie ouverte (TV) : c'est là que revient le focus après un changement. */
internal val LocalTabFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Menu de gauche : icônes seules, il s'ouvre (libellés + genres) quand on y entre avec ←.
 *
 * Quand Android lui donne le focus tout seul (retour d'une fiche : l'élément qui avait le focus
 * disparaît), il reste fermé, sans surbrillance, et rend la main à l'écran. Sinon il s'ouvrait
 * et se refermait aussitôt.
 */
@Composable
private fun SideRail(vm: AppViewModel, onPicked: () -> Unit, enteredWithLeft: () -> Boolean, giveBack: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var stray by remember { mutableStateOf(false) }
    LaunchedEffect(stray) {
        // L'écran replace souvent son focus lui-même (vignette ouverte avant) : on lui laisse un instant.
        repeat(8) {
            if (!stray) return@LaunchedEffect
            delay(if (it == 0) 300 else 250)
            if (stray) giveBack()
        }
    }
    val width by animateDpAsState(
        targetValue = if (expanded) RailExpanded else RailCollapsed,
        animationSpec = tween(260, easing = NfEasing),
        label = "rail-width",
    )
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            // Replié, le menu laisse deviner la grande image de l'accueil qui passe dessous.
            .background(
                if (expanded) {
                    SolidColor(Color(0xFA0A0A0C))
                } else {
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black.copy(alpha = 0.4f))
                },
            )
            .onFocusChanged {
                when {
                    !it.hasFocus -> { expanded = false; stray = false }
                    expanded -> Unit
                    enteredWithLeft() -> { expanded = true; stray = false }
                    else -> stray = true
                }
            }
            .onPreviewKeyEvent { e ->
                if (!stray || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    // On voulait vraiment aller dans le menu : il s'ouvre normalement.
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft -> { expanded = true; stray = false; false }
                    // Pas d'OK sur un élément qu'on ne voit pas : on rend la main à l'écran.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { giveBack(); true }
                    else -> false
                }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NfLogoMark(44.dp)
            if (expanded) Text("NosFlix", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        RailItem(NfIcons.Search, "Rechercher", vm.screen == MainScreen.Search, expanded, onClick = { vm.openSearch(); onPicked() })
        RailItem(NfIcons.Home, "Accueil", vm.screen == MainScreen.Home, expanded, onClick = { vm.goHome(); onPicked() })
        if (!vm.isKid) RailItem(NfIcons.Trending, "Actualité", vm.screen == MainScreen.News, expanded, onClick = { vm.openNews(); onPicked() })
        // Films + son sous-menu de genres (visible quand on est sur Films ou qu'on passe dessus).
        var filmsFocus by remember { mutableStateOf(false) }
        Column(
            Modifier.onFocusChanged { filmsFocus = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RailItem(NfIcons.Film, "Films", isFilms(vm), expanded, onClick = { vm.openBrowse(BrowseKey.Movies); onPicked() })
            if (expanded && (filmsFocus || isFilms(vm))) {
                vm.movieGenres.forEach { g ->
                    RailItem(
                        icon = null,
                        label = g,
                        active = isBrowsing(vm, BrowseKey.Genre(g, "Movie")),
                        expanded = true,
                        onClick = { vm.openBrowse(BrowseKey.Genre(g, "Movie")); onPicked() },
                        dot = genreColor(g),
                    )
                }
            }
        }
        RailItem(NfIcons.Tv, "Séries", isShows(vm), expanded, onClick = { vm.openBrowse(BrowseKey.Shows); onPicked() })
        RailItem(NfIcons.Bookmark, "Ma liste", isBrowsing(vm, BrowseKey.MyList), expanded, onClick = { vm.openBrowse(BrowseKey.MyList); onPicked() })
        RailItem(NfIcons.Music, "Musique", isMusic(vm), expanded, onClick = { vm.openMusic(); onPicked() })
        RailItem(NfIcons.Headphones, "Livres audio", isBooks(vm), expanded, onClick = { vm.openBooks(); onPicked() })
        if (vm.engine.active) {
            RailItem(NfIcons.Play, "En lecture", vm.showNowPlaying, expanded, onClick = { vm.openNowPlaying() })
        }
        Spacer(Modifier.height(12.dp))
        RailItem(NfIcons.Settings, "Paramètres", false, expanded, onClick = { vm.showSettings = true })
    }
}

/**
 * Onglets du haut (TV), calés à gauche avec le contenu, + date et heure à droite.
 * [startPad] : 0 quand l'écran a déjà sa marge (grilles).
 */
@Composable
internal fun TvTopTabs(vm: AppViewModel, startPad: Dp = 32.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp, end = 24.dp),
    ) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = startPad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // L'onglet de la catégorie ouverte reçoit le focus après un changement (voir MainTv).
            val tabFocus = LocalTabFocus.current
            @Composable
            fun Tab(label: String, active: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
                Box(if (active && tabFocus != null) Modifier.focusRequester(tabFocus) else Modifier) {
                    TabItem(label, active, onClick = onClick, fontSize = 13.sp, icon = icon, compact = true)
                }
            }
            Tab("Accueil", vm.screen == MainScreen.Home, onClick = { vm.goHome() })
            if (!vm.isKid) Tab("Actualité", vm.screen == MainScreen.News, onClick = { vm.openNews() })
            Tab("Films", isFilms(vm), onClick = { vm.openBrowse(BrowseKey.Movies) })
            Tab("Séries", isShows(vm), onClick = { vm.openBrowse(BrowseKey.Shows) })
            Tab("Ma liste", isBrowsing(vm, BrowseKey.MyList), onClick = { vm.openBrowse(BrowseKey.MyList) })
            Tab("Musique", isMusic(vm), onClick = { vm.openMusic() })
            Tab("Livres audio", isBooks(vm), onClick = { vm.openBooks() })
            Tab("Rechercher", vm.screen == MainScreen.Search, onClick = { vm.openSearch() }, icon = NfIcons.Search)
        }
        Row(
            Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // La musique ou le livre en cours (→ depuis « Rechercher ») : OK ouvre le lecteur.
            if (vm.engine.active) {
                val e = vm.engine
                val imageId = if (e.kind == AudioKind.Book) e.book?.imageItemId ?: e.current?.imageItemId else e.current?.imageItemId
                NowPlayingPill(e, coverUrl(imageId, 96), onOpen = { vm.openNowPlaying() })
            }
            TvClock(compact = vm.engine.active)
            // Le profil en cours (→ depuis « Rechercher ») : OK ouvre « Qui regarde ? ».
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            vm.currentProfile?.let { me ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(7.dp))
                        .clickable(interactionSource = interaction, indication = null, onClick = { vm.showProfiles() })
                        .padding(2.dp),
                ) {
                    ProfileAvatar(me, 26.dp)
                }
            }
        }
    }
}

@Composable
private fun TvClock(compact: Boolean = false) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(20_000)
        }
    }
    Text(
        // [compact] : l'heure seule, pour laisser la place à la pastille « en lecture ».
        now.format(DateTimeFormatter.ofPattern(if (compact) "HH:mm" else "EEE d MMM · HH:mm", Locale.FRENCH)),
        color = NfColors.TextSoft,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------- Téléphone

@Composable
private fun MainPhone(vm: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = !drawerState.isOpen && vm.screen != MainScreen.Home) { vm.back() }
    // Retour sur l'accueil : on demande avant de quitter l'appli.
    BackHandler(enabled = !drawerState.isOpen && vm.screen == MainScreen.Home) { vm.askExit = true }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF111117), drawerContentColor = NfColors.Text) {
                DrawerContent(vm, closeDrawer)
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(NfColors.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(NfIcons.Menu, "Ouvrir le menu", onClick = { scope.launch { drawerState.open() } })
                NfLogoMark(34.dp)
                Spacer(Modifier.width(10.dp))
                Text("NosFlix", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconAction(
                    NfIcons.Search,
                    "Rechercher",
                    onClick = { vm.openSearch() },
                    tint = if (vm.screen == MainScreen.Search) NfColors.AccentText else Color.White,
                )
                // Le profil en cours : un appui ouvre « Qui regarde ? ».
                vm.currentProfile?.let { me ->
                    ProfileAvatar(
                        me,
                        30.dp,
                        Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.showProfiles() },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TabItem("Accueil", vm.screen == MainScreen.Home, onClick = { vm.goHome() }, fontSize = 15.sp)
                if (!vm.isKid) TabItem("Actualité", vm.screen == MainScreen.News, onClick = { vm.openNews() }, fontSize = 15.sp)
                TabItem("Films", isFilms(vm), onClick = { vm.openBrowse(BrowseKey.Movies) }, fontSize = 15.sp)
                TabItem("Séries", isShows(vm), onClick = { vm.openBrowse(BrowseKey.Shows) }, fontSize = 15.sp)
                TabItem("Ma liste", isBrowsing(vm, BrowseKey.MyList), onClick = { vm.openBrowse(BrowseKey.MyList) }, fontSize = 15.sp)
                TabItem("Musique", isMusic(vm), onClick = { vm.openMusic() }, fontSize = 15.sp)
                TabItem("Livres audio", isBooks(vm), onClick = { vm.openBooks() }, fontSize = 15.sp)
            }
            Box(Modifier.weight(1f)) {
                ScreenContent(vm)
            }
            // Mini-lecteur : la musique ou le livre en cours, toujours à portée de pouce.
            if (vm.engine.active) {
                val e = vm.engine
                val imageId = if (e.kind == AudioKind.Book) e.book?.imageItemId ?: e.current?.imageItemId else e.current?.imageItemId
                MiniPlayer(e, coverUrl(imageId, 160), onOpen = { vm.openNowPlaying() })
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun DrawerContent(vm: AppViewModel, close: () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NfLogoMark(40.dp)
            Text("NosFlix", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconAction(NfIcons.Close, "Fermer le menu", onClick = close)
        }
        RailItem(NfIcons.Home, "Accueil", vm.screen == MainScreen.Home, true, onClick = { vm.goHome(); close() })
        if (!vm.isKid) RailItem(NfIcons.Trending, "Actualité", vm.screen == MainScreen.News, true, onClick = { vm.openNews(); close() })
        RailItem(NfIcons.Film, "Films", isFilms(vm), true, onClick = { vm.openBrowse(BrowseKey.Movies); close() })
        // Sous-menu : les genres de films.
        vm.movieGenres.forEach { g ->
            RailItem(
                icon = null,
                label = g,
                active = isBrowsing(vm, BrowseKey.Genre(g, "Movie")),
                expanded = true,
                onClick = { vm.openBrowse(BrowseKey.Genre(g, "Movie")); close() },
                dot = genreColor(g),
            )
        }
        RailItem(NfIcons.Tv, "Séries", isShows(vm), true, onClick = { vm.openBrowse(BrowseKey.Shows); close() })
        RailItem(NfIcons.Bookmark, "Ma liste", isBrowsing(vm, BrowseKey.MyList), true, onClick = { vm.openBrowse(BrowseKey.MyList); close() })
        RailItem(NfIcons.Music, "Musique", isMusic(vm), true, onClick = { vm.openMusic(); close() })
        RailItem(NfIcons.Headphones, "Livres audio", isBooks(vm), true, onClick = { vm.openBooks(); close() })
        Spacer(Modifier.height(12.dp))
        RailItem(NfIcons.User, "Changer de profil", false, true, onClick = { close(); vm.showProfiles() })
        RailItem(NfIcons.Settings, "Paramètres", false, true, onClick = { vm.showSettings = true; close() })
    }
}

// ---------------------------------------------------------------- Accueil

@Composable
private fun HomeContent(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    when (val h = vm.home) {
        HomeState.Loading -> Column(Modifier.fillMaxSize()) {
            if (isTv) TvTopTabs(vm)
            CenterLoader()
        }
        is HomeState.Error -> Column(Modifier.fillMaxSize()) {
            if (isTv) TvTopTabs(vm)
            ErrorBox(
                message = h.message,
                onRetry = { vm.loadHome() },
                secondaryLabel = "Changer de serveur",
                onSecondary = { vm.logout() },
            )
        }
        is HomeState.Ready -> HomeReady(vm, h)
    }
}

// ---------------------------------------------------------------- Actualité

/** Onglet Actualité : sorties ciné, séries tendance, recommandations, liste d'envies (TMDB). */
@Composable
private fun NewsContent(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    when (val h = vm.home) {
        HomeState.Loading -> Column(Modifier.fillMaxSize()) {
            if (isTv) TvTopTabs(vm)
            CenterLoader()
        }
        is HomeState.Error -> Column(Modifier.fillMaxSize()) {
            if (isTv) TvTopTabs(vm)
            ErrorBox(message = h.message, onRetry = { vm.loadHome() })
        }
        is HomeState.Ready -> when {
            h.news.isEmpty() -> Column(Modifier.fillMaxSize()) {
                if (isTv) TvTopTabs(vm)
                Text(
                    if (vm.trendsEnabled) "Pas d’actualité pour l’instant : TMDB ne répond pas. Réessaie plus tard."
                    else "L’actualité vient de TMDB : ajoute ta clé TMDB dans local.properties pour l’activer.",
                    color = NfColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(d.rowPad),
                )
            }
            // TV : même présentation immersive que l'accueil, avec ses propres lignes.
            isTv -> HomeImmersiveTv(
                vm = vm,
                h = HomeState.Ready(hero = h.news.first().items.firstOrNull(), rows = h.news),
                savedKey = vm.newsFocusKey,
                onFocusKey = { vm.newsFocusKey = it },
            )
            else -> {
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                    item(key = "header") {
                        Column(Modifier.padding(start = d.rowPad, end = d.rowPad, top = 16.dp, bottom = 4.dp)) {
                            Text("ACTUALITÉ", color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                            Text(
                                "Ce qui sort, ce qui buzze",
                                color = Color.White,
                                fontSize = d.titleSize,
                                lineHeight = (d.titleSize.value * 1.15f).sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                            )
                        }
                    }
                    itemsIndexed(h.news, key = { _, row -> row.id }) { _, row ->
                        MediaRowView(
                            row = row,
                            selectedKey = selected,
                            onSelect = { selected = it },
                            onPlay = { vm.openDetail(it) },
                            onMore = null,
                        )
                    }
                }
            }
        }
    }
}

/** « Quitter NosFlix ? » : Retour sur l'accueil ne ferme plus l'appli par erreur. */
@Composable
private fun ExitDialog(vm: AppViewModel) {
    val activity = LocalContext.current as? android.app.Activity
    val stay = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.askExit = false },
        containerColor = NfColors.Surface,
        title = { Text("Quitter NosFlix ?", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            if (vm.engine.playing) {
                Text("La musique ou le livre audio en cours continuera en arrière-plan.", color = NfColors.TextSoft, fontSize = 15.sp)
            }
        },
        confirmButton = { NfButton("Quitter", onClick = { vm.askExit = false; activity?.finish() }) },
        dismissButton = { NfButton("Rester", onClick = { vm.askExit = false }, primary = true, focusRequester = stay) },
    )
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { stay.requestFocus() }
    }
}

/** « Tout marquer comme vu ? » : série ou saison entière. « Annuler » est sélectionné d'office. */
@Composable
private fun PlayedConfirmDialog(vm: AppViewModel, c: PlayedConfirm) {
    val cancel = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.dismissPlayedConfirm() },
        containerColor = NfColors.Surface,
        title = { Text(c.title, color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = { Text(c.message, color = NfColors.TextSoft, fontSize = 15.sp) },
        confirmButton = { NfButton(c.confirmLabel, onClick = { vm.confirmPlayed() }) },
        dismissButton = { NfButton("Annuler", onClick = { vm.dismissPlayedConfirm() }, primary = true, focusRequester = cancel) },
    )
    LaunchedEffect(c) {
        for (wait in listOf(100L, 250L, 500L)) {
            delay(wait)
            if (runCatching { cancel.requestFocus() }.isSuccess) break
        }
    }
}

@Composable
private fun HomeReady(vm: AppViewModel, h: HomeState.Ready) {
    // Sur TV : accueil immersif (grande image du titre sélectionné en haut de l'écran).
    if (LocalIsTv.current) {
        HomeImmersiveTv(vm, h)
        return
    }
    val isTv = false
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val defaultKey = if (isTv) null else h.rows.firstOrNull()?.let { r -> r.items.firstOrNull()?.let { "${r.id}:${it.id}" } }
    val firstFocus = remember { FocusRequester() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (isTv) item(key = "tabs") { TvTopTabs(vm) }
        h.hero?.let { hero ->
            item(key = "hero") {
                HeroBanner(
                    hero,
                    onPlay = { vm.play(context, hero) },
                    onToggleFavorite = { vm.toggleFavorite(hero) },
                    onInfo = { vm.openDetail(hero) },
                )
            }
        }
        if (h.rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (vm.isKid) "Pas encore de programme pour toi : demande à un parent d’en autoriser (sur la fiche d’un titre, « Autoriser pour les enfants »)." else "Ta bibliothèque est vide pour l’instant. Ajoute des films et des séries dans Jellyfin " +
                        "(Tableau de bord → Bibliothèques), puis reviens ici.",
                    color = NfColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(LocalDims.current.rowPad),
                )
            }
        }
        itemsIndexed(h.rows, key = { _, row -> row.id }) { index, row ->
            MediaRowView(
                row = row,
                selectedKey = selected ?: defaultKey,
                onSelect = { selected = it },
                // « Reprendre la lecture » relance directement ; ailleurs, OK ouvre la fiche détaillée.
                onPlay = { if (row.id == "resume") vm.play(context, it) else vm.openDetail(it) },
                onMore = { vm.openMore(it) },
                firstFocus = if (index == 0) firstFocus else null,
            )
        }
    }

    if (isTv && h.rows.isNotEmpty()) {
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

// ---------------------------------------------------------------- Parcourir (Films, Séries, genres…)

@Composable
private fun BrowseContent(vm: AppViewModel) {
    val b = vm.browse ?: return
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val context = LocalContext.current
    var selected by remember(b.key, b.sort) { mutableStateOf<String?>(null) }
    val defaultKey = if (isTv) null else b.rows.firstOrNull()?.let { r -> r.items.firstOrNull()?.let { "${r.id}:${it.id}" } }
    val firstFocus = remember(b.key, b.sort) { FocusRequester() }
    val error = b.error

    // « Voir tout » (un genre, les mieux notés, les ajouts…) : tous les titres en grille.
    if (b.key != BrowseKey.Movies && b.key != BrowseKey.Shows) {
        BrowseGrid(vm, b)
        return
    }
    // TV : Films et Séries ont la même présentation que l'accueil (grande image du titre sélectionné).
    if (isTv && !b.loading && error == null && b.rows.isNotEmpty()) {
        val firstRow = b.rows.first().items
        val hero = firstRow.firstOrNull { it.backdropItemId != null } ?: firstRow.firstOrNull()
        key(b.key, b.sort) {
            HomeImmersiveTv(
                vm = vm,
                h = HomeState.Ready(hero, b.rows),
                savedKey = vm.browseFocus[b.key.title],
                onFocusKey = { vm.browseFocus[b.key.title] = it },
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (isTv) item(key = "tabs") { TvTopTabs(vm) }
        item(key = "header") { BrowseHeader(vm, b) }
        when {
            b.loading -> item(key = "loading") { CenterLoader(Modifier.fillMaxWidth().height(200.dp)) }
            error != null -> item(key = "error") { ErrorBox(error, onRetry = { vm.openBrowse(b.key, b.sort) }) }
            b.rows.isEmpty() -> item(key = "empty") {
                Text(
                    if (b.key == BrowseKey.MyList) "Ta liste est vide. Ajoute des titres avec le bouton « Ma liste »." else "Aucun titre ici pour l’instant.",
                    color = NfColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(d.rowPad),
                )
            }
            else -> itemsIndexed(b.rows, key = { _, row -> row.id }) { index, row ->
                MediaRowView(
                    row = row,
                    selectedKey = selected ?: defaultKey,
                    onSelect = { selected = it },
                    onPlay = { vm.openDetail(it) },
                    onMore = { vm.openMore(it) },
                    firstFocus = if (index == 0) firstFocus else null,
                )
            }
        }
    }

    if (isTv && !b.loading && b.rows.isNotEmpty()) {
        LaunchedEffect(b.key, b.sort) {
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

@Composable
private fun BrowseHeader(vm: AppViewModel, b: BrowseState) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = d.rowPad, end = d.rowPad, top = if (isTv) 6.dp else 16.dp, bottom = 4.dp),
    ) {
        Text(b.key.kicker, color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(
            b.key.title,
            color = Color.White,
            fontSize = d.titleSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (b.count.isNotEmpty()) Text(b.count, color = NfColors.Muted, fontSize = 14.sp)
        Row(
            Modifier
                .padding(top = 14.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trier :", color = NfColors.Muted, fontSize = 13.sp)
            SortOption.entries.forEach { option ->
                NfChip(option.label, option == b.sort, onClick = { vm.setSort(option) })
            }
        }
    }
}

/** Tous les titres d'une catégorie, en grille d'affiches. */
@Composable
private fun BrowseGrid(vm: AppViewModel, b: BrowseState) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val all = b.rows.firstOrNull { it.id == "all" }?.items.orEmpty()
    val firstFocus = remember(b.key, b.sort) { FocusRequester() }
    val error = b.error
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv) 112.dp else 100.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = d.rowPad, end = d.rowPad, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isTv) item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) { TvTopTabs(vm, startPad = 0.dp) }
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { BrowseHeaderInGrid(vm, b) }
        when {
            b.loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { CenterLoader(Modifier.fillMaxWidth().height(200.dp)) }
            error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) { ErrorBox(error, onRetry = { vm.openBrowse(b.key, b.sort) }) }
            all.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (b.key == BrowseKey.MyList) "Ta liste est vide. Ajoute des titres avec le bouton « Ma liste »." else "Aucun titre ici pour l’instant.",
                    color = NfColors.Muted,
                    fontSize = 15.sp,
                )
            }
            else -> gridItems(all, key = { it.id }) { item ->
                val caption = listOfNotNull(item.year?.toString(), item.durationLabel()).joinToString(" · ")
                Box(if (item.id == all.first().id) Modifier.focusRequester(firstFocus) else Modifier) {
                    SearchResultCard(SearchHit(item, caption), onClick = { vm.openDetail(item) })
                }
            }
        }
    }
    if (isTv && !b.loading && all.isNotEmpty()) {
        LaunchedEffect(b.key, b.sort) {
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

/** En-tête de la grille (la grille a déjà ses marges). */
@Composable
private fun BrowseHeaderInGrid(vm: AppViewModel, b: BrowseState) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    Column(Modifier.fillMaxWidth().padding(top = if (isTv) 6.dp else 16.dp, bottom = 4.dp)) {
        Text(b.key.kicker, color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(b.key.title, color = Color.White, fontSize = d.titleSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (b.count.isNotEmpty()) Text(b.count, color = NfColors.Muted, fontSize = 14.sp)
        Row(
            Modifier
                .padding(top = 14.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trier :", color = NfColors.Muted, fontSize = 13.sp)
            SortOption.entries.forEach { option -> NfChip(option.label, option == b.sort, onClick = { vm.setSort(option) }) }
        }
    }
}

// ---------------------------------------------------------------- Recherche

@Composable
private fun SearchContent(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (vm.results.isEmpty() && vm.query.isEmpty()) vm.onQuery("")
    }
    val term = vm.query.trim()
    val title = when {
        term.isEmpty() -> "Suggestions pour toi"
        vm.searching -> "Recherche…"
        else -> plural(vm.results.size, "résultat", "résultats") + " pour « $term »"
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isTv) 7 else 3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = d.rowPad, end = d.rowPad, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isTv) item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) { TvTopTabs(vm, startPad = 0.dp) }
        item(key = "field", span = { GridItemSpan(maxLineSpan) }) {
            NfSearchField(
                value = vm.query,
                onValueChange = { vm.onQuery(it) },
                placeholder = "Titre, acteur, réalisateur…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (isTv) 6.dp else 14.dp),
            )
        }
        if (vm.genres.isNotEmpty()) {
            item(key = "genres", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.genres.forEach { g -> NfChip(g, false, onClick = { vm.openBrowse(BrowseKey.Genre(g)) }) }
                }
            }
        }
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        if (term.isNotEmpty() && !vm.searching && vm.results.isEmpty() && vm.externalResults.isEmpty()) {
            item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                Text("Aucun titre ne correspond. Essaie un autre mot ou le nom d’un acteur.", color = NfColors.Muted, fontSize = 14.sp)
            }
        }
        gridItems(vm.results, key = { it.item.id }) { hit ->
            SearchResultCard(hit, onClick = { vm.openDetail(hit.item) })
        }
        if (vm.externalResults.isNotEmpty()) {
            item(key = "elsewhere", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 10.dp)) {
                    Text("Hors de ta bibliothèque", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("Ouvre la fiche pour voir où le regarder, ou le garder pour plus tard.", color = NfColors.Muted, fontSize = 13.sp)
                }
            }
            gridItems(vm.externalResults, key = { "ext-" + it.item.id }) { hit ->
                SearchResultCard(hit, onClick = { vm.openDetail(hit.item) })
            }
        }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, onClick: () -> Unit) {
    val client: JellyfinClient = LocalClient.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    // Pas de contour : l'affiche sélectionnée grossit légèrement.
    val lift by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = tween(NfMotion.EXPAND_MS, easing = NfEasing),
        label = "search-lift",
    )
    Column(
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .clip(shape)
                .background(NfColors.Surface2),
        ) {
            Text(
                hit.item.displayTitle,
                color = NfColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp),
            )
            AsyncImage(
                model = if (hit.item.external) hit.item.externalPoster else client.imageUrl(hit.item.posterItemId, "Primary", fillHeight = 420),
                contentDescription = hit.item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            LocalFormatBadges.current[hit.item.id]?.let { FormatBadge(it, Modifier.align(Alignment.TopEnd)) }
            if (hit.item.played && !hit.item.external) PlayedMark(Modifier.align(Alignment.TopStart))
        }
        Text(hit.item.displayTitle, color = Color(0xFFE4E4E9), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hit.caption, color = NfColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Paramètres

@Composable
private fun SettingsDialog(vm: AppViewModel) {
    val s = vm.session ?: return
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        containerColor = NfColors.Surface,
        title = { Text("Paramètres", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            val context = LocalContext.current
            var audioPref by remember { mutableStateOf(LanguagePrefs.audio(context)) }
            var subtitlePref by remember { mutableStateOf(LanguagePrefs.subtitles(context)) }
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val address = vm.client.session?.server ?: s.server
                SettingLine("Serveur", address)
                SettingLine("Connexion", ServerRoute.label(address))
                var editing by remember { mutableStateOf<String?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Adresse hors de la maison", color = NfColors.Muted, fontSize = 12.sp)
                        Text(
                            vm.awayServer.ifBlank { if (ServerRoute.hasBuiltInRemote) "Déjà prévue dans l’appli" else "Aucune" },
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NfButton("Modifier", onClick = { editing = "away" })
                }
                if (!vm.tmdbBuiltIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Clé TMDB (affiches, tendances, sorties ciné)", color = NfColors.Muted, fontSize = 12.sp)
                            Text(if (vm.tmdbKey.isBlank()) "Aucune" else "Enregistrée", color = Color.White, fontSize = 15.sp)
                        }
                        NfButton(if (vm.tmdbKey.isBlank()) "Ajouter" else "Modifier", onClick = { editing = "tmdb" })
                    }
                }
                when (editing) {
                    "away" -> TextSettingDialog(
                        title = "Adresse hors de la maison",
                        help = "Une autre adresse du même serveur, utilisée quand celle de la maison ne répond pas : " +
                            "Tailscale (ex. http://100.x.x.x:8096) ou une adresse sur Internet. Laisse vide pour aucune.",
                        initial = vm.awayServer,
                        keyboardType = KeyboardType.Uri,
                        onSave = { vm.saveAwayServer(it); editing = null },
                        onDismiss = { editing = null },
                    )
                    "tmdb" -> TextSettingDialog(
                        title = "Clé TMDB",
                        help = "Gratuite sur themoviedb.org (compte → Paramètres → API). Colle la « clé d’API » ou le « jeton d’accès en lecture ». Laisse vide pour la retirer.",
                        initial = vm.tmdbKey,
                        keyboardType = KeyboardType.Ascii,
                        onSave = { vm.saveTmdbKey(it); editing = null },
                        onDismiss = { editing = null },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Profil", color = NfColors.Muted, fontSize = 12.sp)
                        Text(
                            s.userName.ifBlank { "—" } + if (vm.isKid) " · profil enfant" else "",
                            color = Color.White,
                            fontSize = 15.sp,
                        )
                    }
                    NfButton("Changer de profil", onClick = { vm.showProfiles() })
                }
                // Langue choisie automatiquement au lancement d'une vidéo.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Langue audio", color = Color.White, fontWeight = FontWeight.Medium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioPref.entries.forEach { p ->
                            NfChip(p.label, p == audioPref, onClick = { audioPref = p; LanguagePrefs.setAudio(context, p) })
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sous-titres", color = Color.White, fontWeight = FontWeight.Medium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitlePref.entries.forEach { p ->
                            NfChip(p.label, p == subtitlePref, onClick = { subtitlePref = p; LanguagePrefs.setSubtitles(context, p) })
                        }
                    }
                    Text(subtitlePref.hint.replaceFirstChar { it.uppercase() } + ".", color = NfColors.Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Lecteur externe", color = Color.White, fontWeight = FontWeight.Medium)
                        Text(
                            "Ouvre les vidéos dans MX Player, GOM Player ou VLC au lieu du lecteur intégré.",
                            color = NfColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = vm.externalPlayer,
                        onCheckedChange = { vm.updateExternalPlayer(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NfColors.Accent),
                    )
                }
                SettingLine("Vidéo décodée par la puce de l’appareil", DeviceCodecs.summary())
                SettingLine(
                    "Tendances mondiales (TMDB)",
                    if (vm.trendsEnabled) "Activées" else "Désactivées : ajoute une clé TMDB",
                )
                vm.wishTextUrl?.let { url ->
                    SettingLine("Liste « À voir plus tard », dans un navigateur", url)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Version de l’appli", color = NfColors.Muted, fontSize = 12.sp)
                        Text("${Updater.currentName} du ${Updater.currentDate}", color = Color.White, fontSize = 15.sp)
                    }
                    NfButton("Mise à jour ?", onClick = { vm.checkForUpdate(manual = true) })
                }
                Text(
                    "Ce produit utilise l’API TMDB mais n’est ni approuvé ni certifié par TMDB.",
                    color = NfColors.Subtle,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = { NfButton("Fermer", onClick = { vm.showSettings = false }, primary = true) },
        dismissButton = { NfButton("Se déconnecter", onClick = { vm.logout() }) },
    )
}

/** Petite fenêtre pour taper un réglage (adresse, clé). */
@Composable
private fun TextSettingDialog(
    title: String,
    help: String,
    initial: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NfColors.Surface,
        title = { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(help, color = NfColors.TextSoft, fontSize = 13.sp)
                NfTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = title,
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    onDone = { onSave(value) },
                )
            }
        },
        confirmButton = { NfButton("Enregistrer", onClick = { onSave(value) }, primary = true) },
        dismissButton = { NfButton("Annuler", onClick = onDismiss) },
    )
}

@Composable
private fun SettingLine(label: String, value: String) {
    Column {
        Text(label, color = NfColors.Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
