package fr.nosflix.app.ui.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.audio.AudioEngine
import fr.nosflix.app.audio.AudioKind
import fr.nosflix.app.audio.RepeatMode
import fr.nosflix.app.player.speedLabel
import fr.nosflix.app.ui.LocalIsTv
import fr.nosflix.app.ui.NfButton
import fr.nosflix.app.ui.NfColors
import fr.nosflix.app.ui.NfIcons
import kotlinx.coroutines.delay

private enum class NpDialog { Speed, Sleep }

private val BookSpeeds = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
private val SleepChoices = listOf(0 to "Désactivée", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 heure", -1 to "À la fin du chapitre")

/** Ce que le curseur montre : la position dans le morceau, ou dans le chapitre d'un long fichier. */
private data class Progress(val position: Long, val duration: Long, val offset: Long)

private fun progressOf(engine: AudioEngine): Progress {
    if (engine.kind == AudioKind.Book) {
        val list = engine.chapters
        val c = list.getOrNull(engine.chapterIndex)
        // Un seul fichier découpé en chapitres : le curseur couvre le chapitre en cours.
        if (c != null && (engine.book?.parts?.size ?: 0) <= 1 && list.size > 1) {
            return Progress((engine.positionMs - c.startMs).coerceAtLeast(0), c.durationMs, c.startMs)
        }
    }
    return Progress(engine.positionMs, engine.durationMs, 0L)
}

@Composable
fun NowPlayingScreen(vm: AppViewModel) {
    val engine = vm.engine
    val track = engine.current
    if (track == null) {
        LaunchedEffect(Unit) { vm.showNowPlaying = false }
        return
    }
    val isTv = LocalIsTv.current
    var panel by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<NpDialog?>(null) }
    BackHandler { if (panel) panel = false else vm.showNowPlaying = false }

    val isBook = engine.kind == AudioKind.Book
    val book = engine.book
    val imageId = if (isBook) book?.imageItemId ?: track.imageItemId else track.imageItemId
    val url = coverUrl(imageId, 800)
    // Texte de la pochette maison : le livre, ou l'album (comme sur la page de l'album).
    val title = if (isBook) book?.title ?: track.title else track.album.ifBlank { engine.source.substringAfter(" · ") }.ifBlank { track.title }
    val color = rememberCoverColor(url, title)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(Brush.verticalGradient(0f to color, 0.85f to Color.Black))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (isTv) {
            TvNowPlaying(vm, url, title, onPanel = { panel = true }, onDialog = { dialog = it })
        } else {
            PhoneNowPlaying(vm, url, title, onPanel = { panel = true }, onDialog = { dialog = it })
        }
        if (panel) {
            if (isBook) ChaptersPanel(vm, onClose = { panel = false }) else QueuePanel(vm, onClose = { panel = false })
        }
    }
    when (dialog) {
        NpDialog.Speed -> ChoiceDialog(
            title = "Vitesse de lecture",
            choices = BookSpeeds.map { it to speedLabel(it) },
            selected = BookSpeeds.minByOrNull { kotlin.math.abs(it - engine.speed) },
            onPick = { engine.setPlaybackSpeed(it); dialog = null },
            onDismiss = { dialog = null },
        )
        NpDialog.Sleep -> ChoiceDialog(
            title = "Minuterie de sommeil",
            choices = SleepChoices,
            selected = when {
                engine.sleepEndOfChapter -> -1
                engine.sleepAt != null -> null
                else -> 0
            },
            onPick = {
                engine.setSleep(it)
                if (it != 0) vm.audio.toast(if (it < 0) "Arrêt à la fin du chapitre" else "Arrêt dans $it min")
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

// ---------------------------------------------------------------- Téléphone

@Composable
private fun PhoneNowPlaying(vm: AppViewModel, url: String?, title: String, onPanel: () -> Unit, onDialog: (NpDialog) -> Unit) {
    val engine = vm.engine
    val track = engine.current ?: return
    val isBook = engine.kind == AudioKind.Book
    val book = engine.book
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(AudioIcons.ChevronDown, "Fermer le lecteur", { vm.showNowPlaying = false }, size = 44.dp, iconSize = 26.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isBook) "LIVRE AUDIO" else "LECTURE EN COURS", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Text(engine.source.ifBlank { track.album }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            RoundIconButton(AudioIcons.Queue, if (isBook) "Chapitres" else "File d’attente", onPanel, size = 44.dp)
        }
        Spacer(Modifier.weight(1f))
        AudioCover(
            url,
            title,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            subtitle = if (isBook) book?.author else track.artist,
            corner = 8.dp,
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isBook) title else track.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    if (isBook) currentChapterTitle(engine) else track.artist.ifBlank { track.album },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isBook) {
                val liked = vm.isFavorite(track)
                RoundIconButton(
                    if (liked) AudioIcons.HeartFilled else AudioIcons.Heart,
                    if (liked) "Retirer des titres likés" else "J’aime",
                    { vm.toggleTrackFavorite(track) },
                    size = 44.dp,
                    tint = if (liked) NfColors.AccentText else Color.White,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(engine)
        Spacer(Modifier.height(8.dp))
        Controls(engine, playSize = 68)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (isBook) {
                SmallAction(AudioIcons.Speed, speedLabel(engine.speed)) { onDialog(NpDialog.Speed) }
                SmallAction(AudioIcons.Moon, sleepLabel(engine)) { onDialog(NpDialog.Sleep) }
                SmallAction(AudioIcons.Queue, "Chapitres", onPanel)
            } else {
                SmallAction(AudioIcons.PlaylistAdd, "Ajouter") { vm.audio.askAddToPlaylist(listOf(track)) }
                if (track.albumId != null) SmallAction(AudioIcons.Album, "Album") { vm.openAlbumOf(track) }
                SmallAction(AudioIcons.Queue, "À suivre", onPanel)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- TV

@Composable
private fun TvNowPlaying(vm: AppViewModel, url: String?, title: String, onPanel: () -> Unit, onDialog: (NpDialog) -> Unit) {
    val engine = vm.engine
    val track = engine.current ?: return
    val isBook = engine.kind == AudioKind.Book
    val book = engine.book
    val playFocus = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(44.dp),
    ) {
        AudioCover(url, title, Modifier.size(330.dp), subtitle = if (isBook) book?.author else track.artist, corner = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (isBook) "LIVRE AUDIO" else "EN LECTURE") + (engine.source.takeIf { it.isNotBlank() && !isBook }?.let { " · " + it.uppercase() } ?: ""),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(if (isBook) title else track.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 38.sp)
            Text(
                if (isBook) book?.author.orEmpty() else listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isBook) Text(currentChapterTitle(engine), color = NfColors.TextSoft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            TvProgress(engine, down = playFocus)
            Spacer(Modifier.height(12.dp))
            Controls(engine, playSize = 64, playFocus = playFocus)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isBook) {
                    NfButton("Vitesse " + (if (kotlin.math.abs(engine.speed - 1f) < 0.01f) "1×" else speedLabel(engine.speed)), onClick = { onDialog(NpDialog.Speed) }, icon = AudioIcons.Speed)
                    NfButton(sleepLabel(engine), onClick = { onDialog(NpDialog.Sleep) }, icon = AudioIcons.Moon)
                    NfButton("Chapitres", onClick = onPanel, icon = AudioIcons.Queue)
                } else {
                    NfButton("À suivre", onClick = onPanel, icon = AudioIcons.Queue)
                    val liked = vm.isFavorite(track)
                    NfButton(if (liked) "Liké" else "J’aime", onClick = { vm.toggleTrackFavorite(track) }, icon = if (liked) AudioIcons.HeartFilled else AudioIcons.Heart)
                    NfButton("Ajouter à une playlist", onClick = { vm.audio.askAddToPlaylist(listOf(track)) }, icon = AudioIcons.PlaylistAdd)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }
}

/**
 * Barre d'avancement (TV). ↑ depuis les boutons la sélectionne ; ← / → reculent ou avancent
 * (10 s, 30 s pour un livre, plus vite en restant appuyé) ; OK met en pause ou relance.
 * Le déplacement se fait quand on lâche la touche, pour ne pas sauter à chaque pas.
 */
@Composable
private fun TvProgress(engine: AudioEngine, down: FocusRequester) {
    val p = progressOf(engine)
    val isBook = engine.kind == AudioKind.Book
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var target by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        delay(450)
        engine.seekTo(p.offset + t)
        target = null
    }
    val shown = target ?: p.position
    val fraction = if (p.duration > 0) (shown.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val repeats = e.nativeKeyEvent.repeatCount
                val step = when {
                    repeats > 20 -> 60_000L
                    repeats > 6 -> 30_000L
                    isBook -> 30_000L
                    else -> 10_000L
                }
                val from = target ?: p.position
                when (e.key) {
                    Key.DirectionLeft -> {
                        if (p.duration > 0) target = (from - step).coerceAtLeast(0L)
                        true
                    }
                    Key.DirectionRight -> {
                        if (p.duration > 0) target = (from + step).coerceAtMost((p.duration - 1_000L).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        engine.toggle()
                        true
                    }
                    else -> false
                }
            }
            // ↓ revient sur le bouton lecture (pas sur le bouton le plus proche du curseur).
            .focusProperties { this.down = down }
            .focusable(interactionSource = interaction),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val y = size.height / 2f
            val h = (if (focused) 8.dp else 5.dp).toPx()
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(if (focused) NfColors.Accent else Color.White, Offset(0f, y - h / 2), Size(size.width * fraction, h), CornerRadius(h / 2))
            if (focused) drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(size.width * fraction, y))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(shown), color = if (target != null) Color.White else NfColors.TextSoft, fontSize = 13.sp, fontWeight = if (target != null) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.weight(1f))
            if (focused) Text("◀ ▶  reculer ou avancer · OK  pause", color = NfColors.TextSoft, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(clock(-(p.duration - shown).coerceAtLeast(0)), color = NfColors.TextSoft, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- Pièces communes

@Composable
private fun SeekBar(engine: AudioEngine) {
    val p = progressOf(engine)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (p.duration > 0) (p.position.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
    val seek: (Float) -> Unit = { f -> if (p.duration > 0) engine.seekTo(p.offset + (f * p.duration).toLong()) }
    Column {
        // Barre fine façon Spotify : on touche ou on glisse pour se déplacer.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(p.duration, p.offset) {
                    detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(p.duration, p.offset) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let(seek)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                },
        ) {
            val y = size.height / 2f
            val h = (if (dragging != null) 6.dp else 4.dp).toPx()
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(size.width * fraction, h), CornerRadius(h / 2))
            drawCircle(Color.White, radius = (if (dragging != null) 8.dp else 6.dp).toPx(), center = Offset(size.width * fraction, y))
        }
        Row {
            val shown = dragging?.let { (it * p.duration).toLong() } ?: p.position
            Text(clock(shown), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(clock(-(p.duration - shown).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Controls(engine: AudioEngine, playSize: Int, playFocus: FocusRequester? = null) {
    val isTv = LocalIsTv.current
    val isBook = engine.kind == AudioKind.Book
    Row(
        if (isTv) Modifier else Modifier.fillMaxWidth(),
        horizontalArrangement = if (isTv) Arrangement.spacedBy(14.dp) else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBook) {
            RoundIconButton(AudioIcons.Previous, "Chapitre précédent", { engine.previous() }, size = 50.dp, iconSize = 24.dp)
            RoundIconButton(AudioIcons.Back30, "Reculer de 30 secondes", { engine.seekBy(-30_000) }, size = 54.dp, iconSize = 34.dp, badge = "30")
        } else {
            RoundIconButton(
                AudioIcons.Shuffle,
                if (engine.shuffle) "Aléatoire activé" else "Aléatoire",
                { engine.toggleShuffle() },
                size = 50.dp,
                tint = if (engine.shuffle) NfColors.AccentText else Color.White,
            )
            RoundIconButton(AudioIcons.Previous, "Précédent", { engine.previous() }, size = 54.dp, iconSize = 28.dp)
        }
        PlayButton(engine, playSize, playFocus)
        if (isBook) {
            RoundIconButton(AudioIcons.Forward30, "Avancer de 30 secondes", { engine.seekBy(30_000) }, size = 54.dp, iconSize = 34.dp, badge = "30")
            RoundIconButton(AudioIcons.Next, "Chapitre suivant", { engine.next() }, size = 50.dp, iconSize = 24.dp)
        } else {
            RoundIconButton(AudioIcons.Next, "Suivant", { engine.next() }, size = 54.dp, iconSize = 28.dp)
            RoundIconButton(
                if (engine.repeat == RepeatMode.One) AudioIcons.RepeatOne else AudioIcons.Repeat,
                when (engine.repeat) {
                    RepeatMode.Off -> "Répéter"
                    RepeatMode.All -> "Répéter tout"
                    RepeatMode.One -> "Répéter ce titre"
                },
                { engine.cycleRepeat() },
                size = 50.dp,
                tint = if (engine.repeat != RepeatMode.Off) NfColors.AccentText else Color.White,
            )
        }
    }
}

@Composable
private fun PlayButton(engine: AudioEngine, size: Int, focus: FocusRequester?) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .size(size.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (focused) NfColors.Accent else Color.White)
            .clickable(interactionSource = interaction, indication = null) { engine.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        if (engine.buffering && !engine.playing) {
            androidx.compose.material3.CircularProgressIndicator(color = if (focused) Color.White else Color.Black, strokeWidth = 3.dp, modifier = Modifier.size((size * 0.45f).dp))
        } else {
            Icon(
                if (engine.playing) NfIcons.Pause else NfIcons.Play,
                contentDescription = if (engine.playing) "Pause" else "Lecture",
                tint = if (focused) Color.White else Color.Black,
                modifier = Modifier.size((size * 0.46f).dp),
            )
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
    }
}

private fun currentChapterTitle(engine: AudioEngine): String {
    val list = engine.chapters
    val i = engine.chapterIndex
    val c = list.getOrNull(i) ?: return engine.current?.title.orEmpty()
    return if (list.size > 1) "${i + 1}/${list.size} · ${c.title}" else c.title
}

private fun sleepLabel(engine: AudioEngine): String {
    val at = engine.sleepAt
    return when {
        engine.sleepEndOfChapter -> "Fin du chapitre"
        at != null -> "${((at - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min"
        else -> "Minuterie"
    }
}

// ---------------------------------------------------------------- File d'attente / chapitres

@Composable
private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val isTv = LocalIsTv.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .then(if (isTv) Modifier.width(440.dp) else Modifier.fillMaxWidth())
                .background(Color(0xFC0E0E12))
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 14.dp),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!isTv) RoundIconButton(NfIcons.Close, "Fermer", onClose, size = 44.dp)
            }
            content()
        }
    }
}

@Composable
private fun QueuePanel(vm: AppViewModel, onClose: () -> Unit) {
    val engine = vm.engine
    val current = engine.current ?: return
    val upNext = remember(engine.index, engine.queue, engine.shuffle) { engine.upNext() }
    val first = remember { FocusRequester() }
    PanelFrame("File d’attente", onClose) {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item(key = "now-title") { PanelLabel("En cours") }
            item(key = "now") {
                QueueRow(current.title, current.artist, coverUrl(current.imageItemId, 120), current = true, playing = engine.playing, focus = first) { engine.toggle() }
            }
            if (upNext.isNotEmpty()) item(key = "next-title") { PanelLabel(if (engine.shuffle) "À suivre (aléatoire)" else "À suivre") }
            itemsIndexed(upNext, key = { _, (i, t) -> "q$i-" + t.id }) { _, (i, t) ->
                QueueRow(t.title, t.artist, coverUrl(t.imageItemId, 120), current = false, playing = false) { engine.jumpTo(i) }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, color = NfColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
private fun QueueRow(title: String, sub: String, url: String?, current: Boolean, playing: Boolean, focus: FocusRequester? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AudioCover(url, title, Modifier.size(42.dp), corner = 4.dp, showText = false)
            if (current) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    EqualizerBars(playing)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (current) NfColors.AccentText else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = NfColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChaptersPanel(vm: AppViewModel, onClose: () -> Unit) {
    val engine = vm.engine
    val chapters = engine.chapters
    val currentIndex = engine.chapterIndex
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    PanelFrame(if ((engine.book?.parts?.size ?: 0) > 1) "Parties" else "Chapitres", onClose) {
        LazyColumn(state = state, modifier = Modifier.padding(horizontal = 12.dp)) {
            itemsIndexed(chapters, key = { i, _ -> "ch$i" }) { i, c ->
                ChapterRow(
                    number = i + 1,
                    title = c.title,
                    detail = if ((engine.book?.parts?.size ?: 0) > 1) clock(c.durationMs) else "à " + clock(c.startMs),
                    current = i == currentIndex,
                    playing = engine.playing && i == currentIndex,
                    done = i < currentIndex,
                    onClick = { engine.seekChapter(i); onClose() },
                    focus = if (i == currentIndex) focus else null,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

// ---------------------------------------------------------------- Choix (vitesse, minuterie)

@Composable
private fun <T> ChoiceDialog(title: String, choices: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NfColors.Surface,
        title = { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEachIndexed { i, (value, label) ->
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val isSelected = value == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (isSelected || (selected == null && i == 0)) Modifier.focusRequester(first) else Modifier)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) Color.White else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = null) { onPick(value) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (isSelected) Icon(NfIcons.Check, contentDescription = null, tint = if (focused) Color.Black else NfColors.AccentText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        confirmButton = { NfButton("Fermer", onClick = onDismiss) },
    )
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
}
