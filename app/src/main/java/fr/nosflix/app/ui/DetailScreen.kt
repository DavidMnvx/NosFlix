package fr.nosflix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import fr.nosflix.app.data.MediaItem
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.data.CastMember
import fr.nosflix.app.data.DetailState
import fr.nosflix.app.data.MediaRow
import fr.nosflix.app.data.WatchProviders
import fr.nosflix.app.data.plural
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Fiche détaillée d'un film ou d'une série : grande image, infos, boutons,
 * où le regarder (si ce n'est pas chez toi), casting, saga et titres similaires.
 */
@Composable
fun DetailContent(vm: AppViewModel) {
    val st = vm.detail ?: return
    key(st.item.id) {
        DetailBody(vm, st)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailBody(vm: AppViewModel, st: DetailState) {
    val isTv = LocalIsTv.current
    val client = LocalClient.current
    val context = LocalContext.current
    val item = st.item
    val info = st.info
    val firstFocus = remember { FocusRequester() }
    var selected by remember { mutableStateOf<String?>(null) }
    val imageHeight = if (isTv) 380.dp else 320.dp
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // TV : depuis la première rangée sous les boutons, ↑ remonte tout en haut de la fiche
    // (sinon les boutons, sortis de l'écran, ne sont plus atteignables).
    val firstRow = when {
        st.seasons.isNotEmpty() -> "seasons"
        info != null && info.cast.isNotEmpty() -> "cast"
        st.collection.size > 1 -> "saga"
        st.similar.isNotEmpty() -> "similar"
        else -> null
    }
    val toTop = Modifier.onPreviewKeyEvent { e ->
        if (isTv && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
            scope.launch {
                listState.animateScrollToItem(0)
                runCatching { firstFocus.requestFocus() }
            }
            true
        } else {
            false
        }
    }
    fun rowModifier(key: String): Modifier = if (key == firstRow) toTop else Modifier

    val backdrop = info?.backdropUrl
        ?: if (item.external) {
            item.externalBackdrop ?: item.externalPoster
        } else {
            item.backdropItemId?.let { client.imageUrl(it, "Backdrop", fillWidth = 1280) }
                ?: client.imageUrl(item.posterItemId, "Primary", fillWidth = 900)
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(NfColors.Black),
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .background(
                    if (isTv) {
                        Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.96f), 0.45f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black.copy(alpha = 0.12f))
                    } else {
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.6f))
                    },
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)),
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "header") { DetailHeader(vm, st, firstFocus) }
            // Série : choix de la saison puis ses épisodes.
            if (st.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonsBar(st, onSelect = { vm.selectSeason(it) }, onTogglePlayed = { vm.toggleSeasonPlayed(it) }, modifier = rowModifier("seasons"))
                }
                if (st.episodesLoading) {
                    item(key = "episodes-loading") { CenterLoader(Modifier.fillMaxWidth().height(90.dp)) }
                } else if (st.episodes.isEmpty()) {
                    item(key = "episodes-empty") {
                        Text(
                            "Aucun épisode dans cette saison pour l’instant.",
                            color = NfColors.Muted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = LocalDims.current.rowPad),
                        )
                    }
                }
                items(st.episodes, key = { "ep-" + it.id }) { ep ->
                    EpisodeRow(ep, onClick = { vm.play(context, ep) }, onTogglePlayed = { vm.toggleEpisodePlayed(ep) })
                }
            }
            if (info != null && info.cast.isNotEmpty()) {
                item(key = "cast") { Box(rowModifier("cast")) { CastRow(info.cast) } }
            }
            if (st.collection.size > 1) {
                item(key = "saga") {
                    Box(rowModifier("saga")) {
                        MediaRowView(
                            row = MediaRow("saga", info?.collectionName ?: "La saga", "la saga complète", st.collection),
                            selectedKey = selected,
                            onSelect = { selected = it },
                            onPlay = { vm.openDetail(it) },
                            onMore = null,
                        )
                    }
                }
            }
            if (st.similar.isNotEmpty()) {
                item(key = "similar") {
                    Box(rowModifier("similar")) {
                        MediaRowView(
                            row = MediaRow("similar", "Dans le même esprit", items = st.similar),
                            selectedKey = selected,
                            onSelect = { selected = it },
                            onPlay = { vm.openDetail(it) },
                            onMore = null,
                        )
                    }
                }
            }
            if (st.loading) {
                item(key = "loading") { CenterLoader(Modifier.fillMaxWidth().height(90.dp)) }
            }
        }
        }
    }

    if (isTv) {
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

@Composable
private fun DetailHeader(vm: AppViewModel, st: DetailState, firstFocus: FocusRequester) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val client = LocalClient.current
    val context = LocalContext.current
    val item = st.item
    val info = st.info
    var logoFailed by remember { mutableStateOf(false) }

    val title = info?.title?.takeIf { it.isNotBlank() } ?: item.displayTitle
    val overview = info?.overview?.takeIf { it.isNotBlank() } ?: item.overview
    val duration = when {
        info?.seasons != null -> plural(info.seasons, "saison", "saisons")
        info?.runtimeMinutes != null -> info.runtimeMinutes.let { m -> if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min" }
        else -> item.durationLabel()
    }
    val genres = (info?.genres?.takeIf { it.isNotEmpty() } ?: item.genres).take(3).joinToString(", ")
    val meta = listOfNotNull((info?.year ?: item.year)?.toString(), duration, genres.ifBlank { null }).joinToString(" · ")
    val rating = (info?.rating ?: item.rating)?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.FRANCE, "%.1f", it) }
    val status = when {
        st.playable -> "DANS TA BIBLIOTHÈQUE"
        info?.frenchRelease != null && item.externalBadge?.startsWith("LE ") == true -> "SORTIE EN FRANCE LE ${info.frenchRelease.uppercase(Locale.FRENCH)}"
        else -> "PAS ENCORE DANS TA BIBLIOTHÈQUE"
    }
    val trailer = info?.trailerKey
    val wished = vm.isWished(item)

    Column(
        Modifier
            .padding(start = d.rowPad, end = d.rowPad, top = if (isTv) 64.dp else 150.dp)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            status,
            color = if (st.playable) NfColors.AccentText else NfColors.TextSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        )
        if (!item.external && item.logoItemId != null && !logoFailed) {
            AsyncImage(
                model = client.imageUrl(item.logoItemId, "Logo", fillHeight = 160),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier
                    .heightIn(max = if (isTv) 76.dp else 64.dp)
                    .widthIn(max = 340.dp),
            )
        } else {
            Text(title, color = Color.White, fontSize = d.titleSize, fontWeight = FontWeight.SemiBold, lineHeight = d.titleSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        info?.tagline?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = NfColors.TextSoft, fontSize = 14.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            rating?.let { Text(it, color = NfColors.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            if (meta.isNotBlank()) Text(meta, color = NfColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (overview.isNotBlank()) {
            Text(overview, color = Color(0xFFE4E4E9), fontSize = 14.sp, lineHeight = 20.sp, maxLines = if (isTv) 4 else 7, overflow = TextOverflow.Ellipsis)
        }
        // Format que cet appareil lit mal (souvent l'AV1) : à récupérer dans un autre format.
        LocalFormatBadges.current[item.id]?.let { format ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatBadge(format, Modifier.padding(start = 0.dp))
                Text(
                    if (item.isSeries) "Épisodes en $format : cet appareil risque de ne pas les lire. Mieux vaut une autre version."
                    else "Fichier en $format : cet appareil risque de ne pas le lire. Mieux vaut une autre version.",
                    color = NfColors.AccentText,
                    fontSize = 13.sp,
                )
            }
        }
        if (info != null && info.directors.isNotEmpty()) {
            Text(
                (if (info.isTv) "Création : " else "Réalisation : ") + info.directors.take(2).joinToString(", "),
                color = NfColors.Muted,
                fontSize = 13.sp,
            )
        }

        // Les boutons passent à la ligne s'ils ne tiennent pas (téléphone) plutôt que d'être coupés.
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (st.playable) {
                // Série : le bouton dit quel épisode il lance (« Reprendre S1 · É3 »).
                val next = st.nextUp
                val code = next?.let { listOfNotNull(it.season?.let { s -> "S$s" }, it.episode?.let { e -> "É$e" }).joinToString(" · ") }.orEmpty()
                NfButton(
                    when {
                        item.isSeries && next != null && code.isNotEmpty() -> (if (next.inProgress) "Reprendre " else "Lecture ") + code
                        item.inProgress -> "Reprendre"
                        else -> "Lecture"
                    },
                    onClick = { vm.play(context, item) },
                    icon = NfIcons.Play,
                    primary = true,
                    focusRequester = firstFocus,
                )
            }
            if (trailer != null) {
                NfButton(
                    "Bande-annonce",
                    onClick = { openTrailer(context, trailer) },
                    icon = NfIcons.Film,
                    primary = !st.playable,
                    focusRequester = if (!st.playable) firstFocus else null,
                )
            }
            if (st.playable) {
                NfButton(
                    if (item.favorite) "Dans ma liste" else "Ma liste",
                    onClick = { vm.toggleFavorite(item) },
                    icon = if (item.favorite) NfIcons.Check else NfIcons.Plus,
                )
                // Vu ailleurs, ou fini sans aller jusqu'au générique : on le range parmi les titres vus.
                NfButton(
                    when {
                        item.isSeries && item.played -> "Série vue"
                        item.isSeries -> "Tout marquer vu"
                        item.played -> "Déjà vu"
                        else -> "Marquer comme vu"
                    },
                    onClick = { vm.togglePlayed(item) },
                    icon = if (item.played) NfIcons.Check else NfIcons.Eye,
                )
                // Parents : ce titre est-il visible sur les profils enfants ?
                if (vm.canChooseKidTitles && !item.isEpisode) {
                    val allowed = vm.isKidAllowed(item)
                    NfButton(
                        if (allowed) "Autorisé aux enfants" else "Autoriser pour les enfants",
                        onClick = { vm.toggleKidAllowed(item) },
                        icon = if (allowed) NfIcons.Check else NfIcons.Plus,
                    )
                }
            } else if (item.tmdbId != null && !vm.isKid) {
                NfButton(
                    if (wished) "Dans mes envies" else "À voir plus tard",
                    onClick = { vm.toggleWish(item) },
                    icon = if (wished) NfIcons.Check else NfIcons.Plus,
                    focusRequester = if (trailer == null) firstFocus else null,
                )
            }
        }

        if (!st.playable) ProvidersBlock(info?.providers, st.loading)
    }
}

@Composable
private fun ProvidersBlock(providers: WatchProviders?, loading: Boolean) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Où le regarder en France", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        when {
            loading -> Text("Recherche…", color = NfColors.Muted, fontSize = 13.sp)
            providers == null || providers.isEmpty -> Text(
                "Pas encore proposé en streaming, location ou achat en France (souvent encore au cinéma).",
                color = NfColors.Muted,
                fontSize = 13.sp,
            )
            else -> {
                if (providers.subscription.isNotEmpty()) ProviderLine("Abonnement", providers.subscription)
                if (providers.rent.isNotEmpty()) ProviderLine("Location", providers.rent)
                if (providers.buy.isNotEmpty()) ProviderLine("Achat", providers.buy)
            }
        }
        Text("Données : TMDB · plateformes : JustWatch", color = NfColors.Subtle, fontSize = 11.sp)
    }
}

@Composable
private fun ProviderLine(label: String, names: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label :", color = NfColors.Muted, fontSize = 13.sp)
        Text(names.take(6).joinToString(", "), color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    val d = LocalDims.current
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            "Casting",
            color = Color.White,
            fontSize = d.rowTitle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = d.rowPad),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.rowPad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(cast) { person ->
                // Sélectionnable à la télécommande : on peut parcourir le casting et remonter/descendre.
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val lift by animateFloatAsState(if (focused) 1.1f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "cast-lift")
                Column(
                    Modifier
                        .width(92.dp)
                        .focusable(interactionSource = interaction),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .graphicsLayer { scaleX = lift; scaleY = lift }
                            .clip(CircleShape)
                            .background(if (focused) Color.White else NfColors.Surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString(""),
                            color = NfColors.Muted,
                            fontSize = 18.sp,
                        )
                        AsyncImage(
                            model = person.photoUrl,
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(person.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    if (person.role.isNotBlank()) {
                        Text(person.role, color = NfColors.Muted, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Saisons et épisodes

@Composable
private fun SeasonsBar(st: DetailState, onSelect: (String) -> Unit, onTogglePlayed: (String) -> Unit, modifier: Modifier) {
    val d = LocalDims.current
    Column(modifier.padding(top = 18.dp)) {
        Text(
            "Épisodes",
            color = Color.White,
            fontSize = d.rowTitle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = d.rowPad),
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = d.rowPad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            st.seasons.forEach { s -> NfChip(s.name, s.id == st.seasonId, onClick = { onSelect(s.id) }) }
            // La saison affichée : tout marquer vu d'un coup (ou tout remettre à zéro).
            st.seasons.firstOrNull { it.id == st.seasonId }?.let { s ->
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.2f)))
                PlayedChip(if (s.played) "Saison vue" else "Marquer la saison vue", s.played, onClick = { onTogglePlayed(s.id) })
            }
        }
    }
}

/** Pastille « vu » : œil quand ce n'est pas vu, coche quand c'est vu. */
@Composable
private fun PlayedChip(label: String, played: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val fg = if (focused) Color.Black else Color.White
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.16f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (played) NfIcons.Check else NfIcons.Eye, contentDescription = null, tint = if (played && !focused) NfColors.AccentText else fg, modifier = Modifier.size(16.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Bouton rond au bout d'une ligne d'épisode : coche pleine si l'épisode est vu.
 * TV : → depuis l'épisode pour l'atteindre.
 */
@Composable
private fun EpisodePlayedToggle(played: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> Color.White
        played -> NfColors.Accent
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, if (focused || played) Color.Transparent else Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            NfIcons.Check,
            contentDescription = if (played) "Vu : marquer comme pas vu" else "Marquer comme vu",
            tint = when {
                focused -> Color.Black
                played -> Color.White
                else -> Color.White.copy(alpha = 0.45f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Un épisode : image, numéro et titre, durée, résumé ; barre de progression s'il est commencé. */
@Composable
private fun EpisodeRow(ep: MediaItem, onClick: () -> Unit, onTogglePlayed: () -> Unit) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val client = LocalClient.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // L'épisode et son bouton « vu » sont côte à côte (pas l'un dans l'autre) : → passe de l'un à l'autre.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = d.rowPad - 8.dp, end = d.rowPad - 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .width(if (isTv) 190.dp else 136.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(NfColors.Surface2),
            ) {
                AsyncImage(
                    model = client.imageUrl(ep.id, "Primary", fillWidth = 400),
                    contentDescription = ep.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (focused) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(NfIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
                LocalFormatBadges.current[ep.id]?.let { FormatBadge(it, Modifier.align(Alignment.TopStart)) }
                if (ep.inProgress) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Box(Modifier.fillMaxWidth(((ep.playedPercentage ?: 0.0) / 100.0).toFloat().coerceIn(0.03f, 1f)).height(4.dp).background(NfColors.Accent))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    listOfNotNull(ep.episode?.let { "$it." }, ep.name).joinToString(" "),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(ep.durationLabel(), if (ep.played) "Vu" else ep.remainingLabel()).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = NfColors.Muted, fontSize = 12.sp)
                if (ep.overview.isNotBlank()) {
                    Text(ep.overview, color = NfColors.TextSoft, fontSize = 13.sp, lineHeight = 18.sp, maxLines = if (isTv) 2 else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        EpisodePlayedToggle(ep.played, onTogglePlayed)
    }
}

/** Ouvre la bande-annonce dans l'appli YouTube (ou le navigateur). */
private fun openTrailer(context: Context, youtubeKey: String) {
    val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$youtubeKey".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune appli ne peut ouvrir la bande-annonce (YouTube).", Toast.LENGTH_LONG).show()
    }
}
