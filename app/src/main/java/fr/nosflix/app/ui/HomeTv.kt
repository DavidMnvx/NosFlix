package fr.nosflix.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.data.HomeState
import fr.nosflix.app.data.JellyfinClient
import fr.nosflix.app.data.MediaItem
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Accueil TV « immersif » : la grande image du titre sélectionné occupe le haut de l'écran
 * (elle passe sous le menu de gauche et les onglets), son titre et son résumé restent fixes
 * en haut à gauche, et les lignes défilent en dessous sans jamais couper l'image.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeImmersiveTv(
    vm: AppViewModel,
    h: HomeState.Ready,
    /** Affiche où l'on était (accueil ou Actualité), et où la mémoriser. */
    savedKey: String? = vm.homeFocusKey,
    onFocusKey: (String) -> Unit = { vm.homeFocusKey = it },
) {
    val d = LocalDims.current
    val client = LocalClient.current
    val context = LocalContext.current
    // En revenant (d'une fiche, d'un autre onglet), on retrouve l'affiche où l'on était.
    val restoreKey = remember { savedKey }
    val restoreRowIndex = h.rows.indexOfFirst { it.id == restoreKey?.substringBefore(':') }
    var selected by rememberSaveable { mutableStateOf(restoreKey) }
    val firstFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreRowIndex.coerceAtLeast(0))

    // Clé sélectionnée : « idDeLigne:idDuTitre » (l'id du titre peut lui-même contenir « : »).
    val selectedRowId = selected?.substringBefore(':')
    val selectedItemId = selected?.substringAfter(':', "")
    val selectedRow = h.rows.firstOrNull { it.id == selectedRowId }
    val focused = selectedRow?.items?.firstOrNull { it.id == selectedItemId } ?: h.hero
    val kicker = selectedRow?.title?.uppercase(Locale.FRENCH) ?: "À LA UNE"
    val rowIndex = h.rows.indexOfFirst { it.id == selectedRowId }

    // La ligne sélectionnée vient toujours se caler en haut de la zone des lignes.
    LaunchedEffect(rowIndex) {
        if (rowIndex >= 0) listState.animateScrollToItem(rowIndex)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val imageHeight = maxHeight * 0.74f
        val rowsTop = maxHeight * 0.5f

        // 1. Grande image du titre sélectionné, en fondu, sous le menu et les onglets.
        FadingBackdrop(
            url = backdropUrl(focused, client),
            modifier = Modifier
                .bleedUnderRail()
                .height(imageHeight),
        )
        Box(
            Modifier
                .bleedUnderRail()
                .height(imageHeight)
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.42f to Color.Black.copy(alpha = 0.55f),
                        0.78f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .bleedUnderRail()
                .height(imageHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.6f),
                        0.2f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color.Black,
                    ),
                ),
        )

        // 2. Titre, infos et résumé du titre sélectionné.
        FocusedInfo(
            item = focused,
            kicker = kicker,
            modifier = Modifier
                .padding(start = d.rowPad, top = 60.dp)
                .widthIn(max = 470.dp),
        )

        // 3. Les lignes, dans la moitié basse de l'écran.
        if (h.rows.isEmpty()) {
            Text(
                if (vm.isKid) "Pas encore de programme pour toi : demande à un parent d’en autoriser (sur la fiche d’un titre, « Autoriser pour les enfants »)." else "Ta bibliothèque est vide pour l’instant. Ajoute des films et des séries dans Jellyfin " +
                    "(Tableau de bord → Bibliothèques), puis reviens ici.",
                color = NfColors.Muted,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = d.rowPad, end = d.rowPad, top = rowsTop),
            )
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = rowsTop),
                    contentPadding = PaddingValues(bottom = 160.dp),
                ) {
                    itemsIndexed(h.rows, key = { _, row -> row.id }) { index, row ->
                        MediaRowView(
                            row = row,
                            selectedKey = selected,
                            onSelect = { selected = it; onFocusKey(it) },
                            // « Reprendre la lecture » relance directement ; ailleurs, OK ouvre la fiche.
                            onPlay = { if (row.id == "resume") vm.play(context, it) else vm.openDetail(it) },
                            onMore = { vm.openMore(it) },
                            firstFocus = if (index == 0) firstFocus else null,
                            restoreKey = if (index == restoreRowIndex) restoreKey else null,
                            restoreFocus = if (index == restoreRowIndex) restoreFocus else null,
                        )
                    }
                }
            }
        }

        // 4. Onglets par-dessus l'image.
        TvTopTabs(vm)
    }

    if (h.rows.isNotEmpty()) {
        LaunchedEffect(Unit) {
            delay(150)
            val restored = restoreRowIndex >= 0 && runCatching { restoreFocus.requestFocus() }.isSuccess
            if (!restored) runCatching { firstFocus.requestFocus() }
        }
    }
}

@Composable
private fun FocusedInfo(item: MediaItem?, kicker: String, modifier: Modifier) {
    if (item == null) return
    val client = LocalClient.current
    AnimatedContent(
        targetState = item,
        contentKey = { it.id },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
        modifier = modifier,
        label = "focused-info",
    ) { shown ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kicker, color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1)
            var logoFailed by remember(shown.id) { mutableStateOf(false) }
            if (!shown.external && shown.logoItemId != null && !logoFailed) {
                AsyncImage(
                    model = client.imageUrl(shown.logoItemId, "Logo", fillHeight = 160),
                    contentDescription = shown.displayTitle,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier
                        .heightIn(max = 72.dp)
                        .widthIn(max = 320.dp),
                )
            } else {
                Text(
                    shown.displayTitle,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 34.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                shown.ratingLabel()?.let { Text(it, color = NfColors.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Text(
                    shown.episodeLine() ?: shown.metaLine(),
                    color = NfColors.TextSoft,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shown.overview.isNotBlank()) {
                Text(
                    shown.overview,
                    color = Color(0xFFE4E4E9),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Image de fond qui change en fondu : l'image précédente sert de transition
 * pendant le chargement de la suivante (pas de flash noir).
 */
@Composable
internal fun FadingBackdrop(url: String?, modifier: Modifier) {
    val context = LocalPlatformContext.current
    var previous by remember { mutableStateOf<String?>(null) }
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(450)
            .apply { previous?.let { placeholderMemoryCacheKey(it) } }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier,
    )
    LaunchedEffect(url) { previous = url }
}

private fun backdropUrl(item: MediaItem?, client: JellyfinClient): String? = when {
    item == null -> null
    item.external -> item.externalBackdrop ?: item.externalPoster
    item.backdropItemId != null -> client.imageUrl(item.backdropItemId, "Backdrop", fillWidth = 1280)
    else -> client.imageUrl(item.posterItemId, "Primary", fillWidth = 900)
}

/** Étend un élément vers la gauche, sous le menu replié, pour une image bord à bord. */
internal fun Modifier.bleedUnderRail(): Modifier = layout { measurable, constraints ->
    val extra = RailCollapsed.roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
