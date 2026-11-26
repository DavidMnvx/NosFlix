package fr.nosflix.app.ui.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import fr.nosflix.app.ui.NfSearchField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.audio.AudioKind
import fr.nosflix.app.data.AudioBook
import fr.nosflix.app.data.BookSort
import fr.nosflix.app.data.BooksState
import fr.nosflix.app.data.plural
import fr.nosflix.app.ui.CenterLoader
import fr.nosflix.app.ui.ErrorBox
import fr.nosflix.app.ui.LocalDims
import fr.nosflix.app.ui.LocalIsTv
import fr.nosflix.app.ui.MinimalScroll
import fr.nosflix.app.ui.NfButton
import fr.nosflix.app.ui.NfChip
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

// ---------------------------------------------------------------- Bibliothèque de livres audio

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BooksHomeContent(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val lib = vm.audio
    val (savedIndex, savedOffset) = lib.booksScroll
    val gridState = rememberLazyGridState(savedIndex, savedOffset)
    DisposableEffect(Unit) {
        onDispose { lib.booksScroll = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
    }
    val restore = remember { FocusRequester() }
    val restoreKey = lib.booksFocusKey
    val state = lib.books

    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 120.dp else 104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = d.rowPad, end = d.rowPad, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (isTv) item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) { TvTopTabs(vm, startPad = 0.dp) }
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                val ready = state as? BooksState.Ready
                Column(Modifier.padding(top = if (isTv) 4.dp else 16.dp)) {
                    Text("LIVRES AUDIO", color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    Text("Tes livres audio", color = Color.White, fontSize = d.titleSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (ready != null) {
                        val going = ready.books.count { vm.bookInProgress(it) }
                        Text(
                            plural(ready.books.size, "livre", "livres") + if (going > 0) " · $going en cours" else "",
                            color = NfColors.Muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            item(key = "tools", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NfSearchField(
                        value = lib.bookQuery,
                        onValueChange = { lib.bookQuery = it },
                        placeholder = "Titre ou auteur…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Trier :", color = NfColors.Muted, fontSize = 13.sp)
                        BookSort.entries.forEach { s -> NfChip(s.label, s == lib.bookSort, onClick = { lib.bookSort = s }) }
                    }
                }
            }
            when (state) {
                BooksState.Loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { CenterLoader(Modifier.fillMaxWidth().height(220.dp)) }
                is BooksState.Error -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) { ErrorBox(state.message, onRetry = { lib.loadBooks() }) }
                is BooksState.Ready -> {
                    val going = state.books.filter { vm.bookInProgress(it) }.sortedByDescending { vm.bookLastListen(it) }
                    if (going.isNotEmpty() && lib.bookQuery.isBlank()) {
                        item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Continuer l’écoute", color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold)
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    listItems(going, key = { "go-" + it.id }) { b ->
                                        val key = "go:" + b.id
                                        ContinueCard(vm, b, focus = if (key == restoreKey) restore else null) {
                                            lib.booksFocusKey = key
                                            vm.resumeBook(b)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val shown = lib.visibleBooks(state.books)
                    if (shown.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (state.books.isEmpty()) "Aucun livre audio pour l’instant. Ajoute une bibliothèque « Livres » dans Jellyfin." else "Aucun livre ne correspond.",
                                color = NfColors.Muted,
                                fontSize = 15.sp,
                            )
                        }
                    } else {
                        item(key = "all-title", span = { GridItemSpan(maxLineSpan) }) {
                            Text("Tous les livres", color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    gridItems(shown, key = { "b-" + it.id }) { b ->
                        val key = "b:" + b.id
                        BookTile(b, progress = if (vm.bookStarted(b)) fraction(vm.bookListened(b), b.totalMs) else null, focus = if (key == restoreKey) restore else null) {
                            lib.booksFocusKey = key
                            vm.openBook(b)
                        }
                    }
                }
            }
        }
    }
    if (isTv && state is BooksState.Ready && restoreKey != null) {
        LaunchedEffect(Unit) {
            delay(200)
            runCatching { restore.requestFocus() }
        }
    }
}

@Composable
private fun rememberLiftOf(interaction: MutableInteractionSource): Float {
    val focused by interaction.collectIsFocusedAsState()
    val lift by animateFloatAsState(if (focused) 1.07f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "book-lift")
    return lift
}

@Composable
private fun BookTile(book: AudioBook, progress: Float?, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = rememberLiftOf(interaction)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { scaleX = lift; scaleY = lift },
        ) {
            AudioCover(coverUrl(book.imageItemId), book.title, Modifier.fillMaxSize(), subtitle = book.author)
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            if (book.finished) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(NfIcons.Check, contentDescription = "Terminé", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Text(book.title, color = if (focused) Color.White else Color(0xFFE4E4E9), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        book.author?.let { Text(it, color = NfColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .height(4.dp)
                .background(NfColors.Accent),
        )
    }
}

/** Carte « Continuer l'écoute » : couverture, titre, où tu en es, reprise en un appui. */
@Composable
private fun ContinueCard(vm: AppViewModel, book: AudioBook, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = rememberLiftOf(interaction)
    val live = vm.engine.kind == AudioKind.Book && vm.engine.book?.id == book.id
    Row(
        Modifier
            .width(if (LocalIsTv.current) 300.dp else 280.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else NfColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AudioCover(coverUrl(book.imageItemId, 240), book.title, Modifier.size(92.dp), corner = 0.dp, subtitle = book.author)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val fg = if (focused) Color.Black else Color.White
            val soft = if (focused) Color(0xFF3A3A44) else NfColors.Muted
            Text(book.title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            book.author?.let { Text(it, color = soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (focused) Color(0x33000000) else Color.White.copy(alpha = 0.15f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction(vm.bookListened(book), book.totalMs).coerceIn(0.02f, 1f)).height(3.dp).background(NfColors.Accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) EqualizerBars(vm.engine.playing, Modifier.size(12.dp))
                Text("il reste " + longDuration(book.totalMs - vm.bookListened(book)), color = soft, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- Fiche d'un livre

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookContent(vm: AppViewModel) {
    val book = vm.audio.book ?: run {
        CenterLoader()
        return
    }
    val isTv = LocalIsTv.current
    val d = LocalDims.current
    val engine = vm.engine
    val live = engine.kind == AudioKind.Book && engine.book?.id == book.id
    val url = coverUrl(book.imageItemId, 600)
    val color = rememberCoverColor(url, book.title)
    val chapters = remember(book) { engine.chaptersOf(book) }
    val playFocus = remember { FocusRequester() }
    val (resumePart, resumeMs) = vm.bookResume(book)

    // Progression : en direct si le livre joue, sinon celle enregistrée sur le serveur.
    val listened = if (live) {
        book.parts.take(engine.index.coerceAtLeast(0)).sumOf { it.durationMs } + engine.positionMs
    } else {
        vm.bookListened(book)
    }
    val progress = if (book.totalMs > 0) (listened.toFloat() / book.totalMs).coerceIn(0f, 1f) else 0f
    val started = live || vm.bookStarted(book)
    val currentChapter = when {
        live -> engine.chapterIndex
        started -> chapters.indexOfLast { it.partIndex < resumePart || (it.partIndex == resumePart && it.startMs <= resumeMs) }
        else -> -1
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to lerpToBlack(color, 0.15f), 0.6f to Color.Black)),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "header") {
                val info: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("LIVRE AUDIO", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                        Text(book.title, color = Color.White, fontSize = if (isTv) 32.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = if (isTv) 36.sp else 28.sp)
                        book.author?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                        Text(
                            listOfNotNull(
                                longDuration(book.totalMs),
                                if (book.parts.size > 1) plural(book.parts.size, "partie", "parties") else if (chapters.size > 1) plural(chapters.size, "chapitre", "chapitres") else null,
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                        )
                        if (started) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(260.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.18f)),
                            ) { Box(Modifier.fillMaxWidth(progress.coerceIn(0.01f, 1f)).height(4.dp).background(NfColors.Accent)) }
                            Text(
                                if (book.finished && !live) "Terminé" else "${(progress * 100).toInt()} % écouté · il reste ${longDuration(book.totalMs - listened)}",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            NfButton(
                                text = when {
                                    live && engine.playing -> "Pause"
                                    started -> "Reprendre"
                                    else -> "Écouter"
                                },
                                icon = if (live && engine.playing) NfIcons.Pause else NfIcons.Play,
                                onClick = {
                                    if (live && engine.playing) engine.pause() else vm.resumeBook(book)
                                },
                                primary = true,
                                focusRequester = playFocus,
                            )
                            if (started) {
                                NfButton("Depuis le début", onClick = {
                                    engine.playBook(book, 0, 0L)
                                    vm.showNowPlaying = true
                                })
                            }
                        }
                    }
                }
                if (isTv) {
                    Row(
                        Modifier.padding(start = d.rowPad, end = d.rowPad, top = 28.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AudioCover(url, book.title, Modifier.size(200.dp), subtitle = book.author)
                        info()
                    }
                } else {
                    Column {
                        Box(
                            Modifier
                                .padding(start = 4.dp, top = 4.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable { vm.back() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(NfIcons.Back, contentDescription = "Retour", tint = Color.White, modifier = Modifier.size(22.dp)) }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AudioCover(url, book.title, Modifier.size(210.dp), subtitle = book.author)
                        }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.padding(horizontal = d.rowPad)) { info() }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            item(key = "chapters-title") {
                Text(
                    if (book.parts.size > 1) "Parties" else "Chapitres",
                    color = Color.White,
                    fontSize = d.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = d.rowPad, end = d.rowPad, top = 12.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(chapters, key = { i, _ -> "c$i" }) { i, c ->
                val part = book.parts.getOrNull(c.partIndex)
                val done = when {
                    live -> i < engine.chapterIndex
                    book.parts.size > 1 -> part?.played == true
                    else -> currentChapter > i
                }
                Box(Modifier.padding(horizontal = d.rowPad - 8.dp)) {
                    ChapterRow(
                        number = i + 1,
                        title = c.title,
                        detail = if (book.parts.size > 1) clock(c.durationMs) else "à " + clock(c.startMs),
                        current = i == currentChapter,
                        playing = live && engine.playing && i == currentChapter,
                        done = done,
                        onClick = {
                            engine.playBook(book, c.partIndex, c.startMs)
                            vm.showNowPlaying = true
                        },
                    )
                }
            }
        }
    }
    if (isTv) {
        LaunchedEffect(book.id) {
            delay(150)
            runCatching { playFocus.requestFocus() }
        }
    }
}

@Composable
fun ChapterRow(number: Int, title: String, detail: String, current: Boolean, playing: Boolean, done: Boolean, onClick: () -> Unit, focus: FocusRequester? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            when {
                current -> EqualizerBars(playing)
                done -> Icon(NfIcons.Check, contentDescription = "Écouté", tint = NfColors.Subtle, modifier = Modifier.size(16.dp))
                else -> Text(number.toString(), color = NfColors.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        Text(
            title,
            color = when {
                current -> NfColors.AccentText
                done -> NfColors.Subtle
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, color = NfColors.Muted, fontSize = 13.sp, maxLines = 1)
    }
}

private fun fraction(part: Long, total: Long): Float = if (total > 0) (part.toFloat() / total).coerceIn(0f, 1f) else 0f
