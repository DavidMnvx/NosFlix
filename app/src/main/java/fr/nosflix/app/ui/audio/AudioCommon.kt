package fr.nosflix.app.ui.audio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import fr.nosflix.app.audio.AudioEngine
import fr.nosflix.app.audio.AudioKind
import fr.nosflix.app.ui.NfColors
import fr.nosflix.app.ui.NfEasing
import fr.nosflix.app.ui.NfIcons
import fr.nosflix.app.ui.NfMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ---------------------------------------------------------------- Icônes

/** Icônes au trait (24 × 24) décrites comme en SVG. */
private fun svgIcon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            if (filled) {
                addPath(nodes, fill = SolidColor(Color.White))
            } else {
                addPath(
                    nodes,
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
    }.build()

object AudioIcons {
    val Shuffle = svgIcon("shuffle", "M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val Repeat = svgIcon("repeat", "M17 1l4 4-4 4", "M3 11V9a4 4 0 0 1 4-4h14", "M7 23l-4-4 4-4", "M21 13v2a4 4 0 0 1-4 4H3")
    val RepeatOne = svgIcon("repeat-one", "M17 1l4 4-4 4", "M3 11V9a4 4 0 0 1 4-4h14", "M7 23l-4-4 4-4", "M21 13v2a4 4 0 0 1-4 4H3", "M11 10l1.5-1v6")
    val Previous = svgIcon("previous", "M19 20L9 12l10-8z", "M5 4h2.2v16H5z", filled = true)
    val Next = svgIcon("next", "M5 4l10 8-10 8z", "M16.8 4H19v16h-2.2z", filled = true)
    val Back30 = svgIcon("back-30", "M3 12a9 9 0 1 0 2.6-6.4", "M3 3.5v5h5")
    val Forward30 = svgIcon("forward-30", "M21 12a9 9 0 1 1-2.6-6.4", "M21 3.5v5h-5")
    val Moon = svgIcon("moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Heart = svgIcon("heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21.2l7.8-7.8 1-1.1a5.5 5.5 0 0 0 0-7.7z")
    val HeartFilled = svgIcon("heart-filled", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21.2l7.8-7.8 1-1.1a5.5 5.5 0 0 0 0-7.7z", filled = true)
    val More = svgIcon("more", "M12 7a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z", "M12 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z", "M12 20.2a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z", filled = true)
    val Queue = svgIcon("queue", "M8 6h13", "M8 12h13", "M8 18h13", "M3.5 6h.01", "M3.5 12h.01", "M3.5 18h.01")
    val ChevronDown = svgIcon("chevron-down", "M6 9l6 6 6-6")
    val PlaylistAdd = svgIcon("playlist-add", "M3 6h12", "M3 12h12", "M3 18h7", "M18 13v8", "M14 17h8")
    val Book = svgIcon("book", "M4 19.5A2.5 2.5 0 0 1 6.5 17H20", "M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z")
    val Speed = svgIcon("speed", "M4 17a8 8 0 1 1 16 0", "M12 17l4-6")
    val PlayNext = svgIcon("play-next", "M3 6h11", "M3 12h7", "M3 18h7", "M15 11v8l6-4z")
    val Album = svgIcon("album", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z")
    val Minus = svgIcon("minus", "M5 12h14")
}

// ---------------------------------------------------------------- Pochettes

private fun hashHue(text: String): Float = ((text.hashCode() and 0x7fffffff) % 360).toFloat()

/** Couleur de repli d'une pochette, tirée du titre. */
fun fallbackColor(text: String): Color = Color.hsv(hashHue(text), 0.5f, 0.38f)

/**
 * Pochette d'album ou couverture de livre. Sans image sur le serveur, on dessine une couverture
 * maison : dégradé de couleur, titre et auteur.
 */
@Composable
fun AudioCover(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    corner: Dp = 6.dp,
    showText: Boolean = true,
) {
    val hue = hashHue(title)
    val shape = RoundedCornerShape(corner)
    // Le texte de la couverture maison disparaît dès que la vraie image est chargée.
    var loaded by remember(url) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    0f to Color.hsv(hue, 0.55f, 0.46f),
                    1f to Color.hsv((hue + 35f) % 360f, 0.65f, 0.12f),
                ),
            ),
    ) {
        if (showText && !loaded) {
            val w = maxWidth.value
            Column(
                Modifier
                    .fillMaxSize()
                    .padding((w * 0.09f).dp),
            ) {
                Box(
                    Modifier
                        .width((w * 0.16f).dp)
                        .height((w * 0.025f).coerceAtLeast(1.5f).dp)
                        .background(NfColors.Accent),
                )
                Spacer(Modifier.height((w * 0.06f).dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = (w * 0.105f).coerceIn(8f, 30f).sp,
                    lineHeight = (w * 0.125f).coerceIn(10f, 36f).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = (w * 0.075f).coerceIn(7f, 20f).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Couleur dominante d'une pochette (pour le dégradé du lecteur et des pages d'album). */
@Composable
fun rememberCoverColor(url: String?, fallbackKey: String): Color {
    val context = LocalContext.current
    var color by remember(url, fallbackKey) { mutableStateOf(fallbackColor(fallbackKey)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val rgb = withContext(Dispatchers.Default) {
            val p = Palette.from(bitmap).generate()
            (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb
        } ?: return@LaunchedEffect
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.75f)
        hsv[2] = hsv[2].coerceIn(0.22f, 0.48f)
        color = Color(android.graphics.Color.HSVToColor(hsv))
    }
    val animated by animateColorAsState(color, tween(500), label = "cover-color")
    return animated
}

// ---------------------------------------------------------------- Petites pièces

/** Trois barres qui dansent à côté du morceau en cours. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = NfColors.AccentText) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 180, 90).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420, delayMillis = delay, easing = NfEasing), RepeatMode.Reverse),
            label = "eq-bar",
        )
    }
    Canvas(modifier.size(16.dp)) {
        val barW = size.width / 5f
        phases.forEachIndexed { i, phase ->
            val h = size.height * (if (playing) phase.value else 0.3f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** Bouton rond au trait (lecteur) : fond blanc quand il a le focus de la télécommande. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    tint: Color = Color.White,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "round-scale")
    val bg = when {
        focused -> Color.White
        filled -> Color.White
        else -> Color.Transparent
    }
    val fg = when {
        focused || filled -> Color.Black
        else -> tint
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(iconSize))
        if (badge != null) {
            Text(badge, color = fg, fontSize = (iconSize.value * 0.34f).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = (iconSize.value * 0.12f).dp))
        }
    }
}

// ---------------------------------------------------------------- Mini-lecteur (téléphone)

@Composable
fun MiniPlayer(engine: AudioEngine, imageUrl: String?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = engine.current ?: return
    val isBook = engine.kind == AudioKind.Book
    val title = if (isBook) engine.book?.title ?: track.title else track.title
    val sub = if (isBook) track.title else track.artist
    val coverText = if (isBook) engine.book?.title.orEmpty() else track.album.ifBlank { engine.source.substringAfter(" · ") }.ifBlank { track.title }
    val bg = rememberCoverColor(imageUrl, coverText)
    val progress = if (engine.durationMs > 0) (engine.positionMs.toFloat() / engine.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(lerpToBlack(bg, 0.35f))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AudioCover(imageUrl, coverText, Modifier.size(42.dp), corner = 4.dp, showText = false)
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(playTime(engine), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                }
            }
            if (isBook) RoundIconButton(AudioIcons.Back30, "Reculer de 30 secondes", { engine.seekBy(-30_000) }, size = 40.dp, iconSize = 24.dp, badge = "30")
            RoundIconButton(if (engine.playing) NfIcons.Pause else NfIcons.Play, if (engine.playing) "Pause" else "Lecture", { engine.toggle() }, size = 40.dp, iconSize = 24.dp)
            if (!isBook) RoundIconButton(AudioIcons.Next, "Suivant", { engine.next() }, size = 40.dp, iconSize = 20.dp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(1.dp))
    }
}

// ---------------------------------------------------------------- En lecture (TV, en haut à droite)

/**
 * Pastille « en lecture » de la barre du haut (TV) : pochette, titre, artiste, temps,
 * et une fine barre rouge qui avance. OK ouvre le lecteur.
 */
@Composable
fun NowPlayingPill(engine: AudioEngine, imageUrl: String?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = engine.current ?: return
    val isBook = engine.kind == AudioKind.Book
    val title = if (isBook) engine.book?.title ?: track.title else track.title
    val sub = if (isBook) track.title else track.artist
    val coverText = if (isBook) engine.book?.title.orEmpty() else track.album.ifBlank { track.title }
    val progress = if (engine.durationMs > 0) (engine.positionMs.toFloat() / engine.durationMs).coerceIn(0f, 1f) else 0f
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Fond sombre pour rester lisible sur la grande image de l'accueil ; plus clair avec le focus.
    val bg by animateColorAsState(if (focused) Color(0xFF3A3A42) else Color(0xCC15151A), tween(NfMotion.EXPAND_MS), label = "pill-bg")
    val accent = NfColors.Accent
    Row(
        modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .drawBehind {
                // Avancement du morceau, collé au bas de la pastille.
                val h = 2.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.15f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                drawRect(accent, topLeft = Offset(0f, size.height - h), size = Size(size.width * progress, h))
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(start = 3.dp, end = 9.dp, top = 3.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Interlignes serrés : la pastille ne doit pas être plus haute que les onglets (sinon la page saute).
        AudioCover(imageUrl, coverText, Modifier.size(24.dp), corner = 3.dp, showText = false)
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sub, color = NfColors.TextSoft, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(playTime(engine), color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
            }
        }
        if (engine.playing) {
            EqualizerBars(true, Modifier.size(12.dp))
        } else {
            Icon(NfIcons.Pause, contentDescription = "En pause", tint = NfColors.TextSoft, modifier = Modifier.size(12.dp))
        }
    }
}

/** « 1:23 / 3:45 » : où on en est dans le morceau (ou la partie du livre). */
fun playTime(engine: AudioEngine): String =
    if (engine.durationMs > 0) "${clock(engine.positionMs)} / ${clock(engine.durationMs)}" else clock(engine.positionMs)

fun lerpToBlack(color: Color, amount: Float): Color =
    Color(color.red * (1 - amount), color.green * (1 - amount), color.blue * (1 - amount), 1f)

// ---------------------------------------------------------------- Textes

/** « 12 h 30 » ou « 48 min ». */
fun longDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return when {
        minutes >= 60 -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
        minutes > 0 -> "$minutes min"
        ms > 0 -> "${(ms / 1000).coerceAtLeast(1)} s"
        else -> "—"
    }
}

/** « 3:42 » ou « 1:02:03 ». */
fun clock(ms: Long): String {
    val totalSeconds = (abs(ms) / 1000)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val sign = if (ms < 0) "−" else ""
    return sign + if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
