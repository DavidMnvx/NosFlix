package fr.nosflix.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.nosflix.app.data.MediaItem
import fr.nosflix.app.data.MediaRow
import fr.nosflix.app.data.MoreLink

internal fun Modifier.optionalFocus(requester: FocusRequester?): Modifier =
    if (requester != null) this.focusRequester(requester) else this

/** Champ de recherche (télécommande) : ↓ quitte le champ pour descendre vers les résultats. */
@Composable
fun Modifier.downLeavesField(): Modifier {
    val focusManager = LocalFocusManager.current
    return this.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) focusManager.moveFocus(FocusDirection.Down) else false
    }
}

/** Touche « Rechercher » du clavier : on descend vers les résultats (le clavier se ferme). */
@Composable
fun searchKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    return KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) })
}

/**
 * Champ de recherche (Rechercher, Musique, Livres audio).
 *
 * TV : passer sur le champ avec la télécommande n'ouvre plus le clavier. Il reste en lecture seule
 * jusqu'à ce qu'on appuie sur OK, qui ouvre le clavier. ↓ quitte le champ vers les résultats.
 */
@Composable
fun NfSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(NfIcons.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
        singleLine = true,
        readOnly = isTv && !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = searchKeyboardActions(),
        colors = nfFieldColors(),
        modifier = modifier
            .onFocusChanged { if (!it.hasFocus) editing = false }
            .onPreviewKeyEvent { e ->
                if (!isTv || e.key !in OkKeys) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) {
                    if (editing) keyboard?.show() else editing = true
                }
                true
            }
            .downLeavesField(),
    )
}

/** Touches « OK » des télécommandes. */
private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

// ---------------------------------------------------------------- Boutons

@Composable
fun NfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        !enabled -> NfColors.Surface2
        focused -> Color.White
        primary -> NfColors.Accent
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = when {
        !enabled -> NfColors.Muted
        focused -> Color.Black
        else -> Color.White
    }
    Row(
        modifier
            .optionalFocus(focusRequester)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun NfChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> Color.White
        selected -> NfColors.Accent
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Color(0xFFE4E4E9)
    }
    val borderColor = if (focused || selected) Color.Transparent else Color.White.copy(alpha = 0.16f)
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Texte cliquable (« Voir plus »), blanc sur rouge quand il a le focus. */
@Composable
fun FocusText(text: String, onClick: () -> Unit, fontSize: TextUnit = 15.sp) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text,
        color = if (focused) Color.White else NfColors.AccentText,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) NfColors.Accent else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.White) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Onglet du haut : texte gris, blanc + trait rouge quand il est actif. */
@Composable
fun TabItem(label: String, active: Boolean, onClick: () -> Unit, fontSize: TextUnit, icon: ImageVector? = null, compact: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color = if (active || focused) Color.White else NfColors.Muted
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(if (compact) 17.dp else 22.dp))
        } else {
            // Version compacte (TV) : texte plus fin, pour un bandeau discret.
            Text(label, color = color, fontSize = fontSize, fontWeight = if (compact && !active) FontWeight.Normal else FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(if (compact) 3.dp else 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 2.dp else 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) NfColors.Accent else Color.Transparent),
        )
    }
}

/** Entrée du menu de gauche (TV) ou du tiroir (téléphone). */
@Composable
fun RailItem(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    dot: Color? = null,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    // Menu replié qui a reçu le focus tout seul : pas de surbrillance (voir SideRail).
    val focused = interaction.collectIsFocusedAsState().value && expanded
    val bg = when {
        focused -> Color.White
        active -> NfColors.Accent.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color.Black
        !enabled -> NfColors.Subtle
        active -> NfColors.AccentText
        else -> NfColors.TextSoft
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (icon == null) 34.dp else 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
        } else {
            Box(
                Modifier
                    .padding(horizontal = 7.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot ?: fg),
            )
        }
        if (expanded) {
            Text(
                label,
                color = fg,
                fontSize = if (icon == null) 13.sp else 14.sp,
                fontWeight = if (icon == null) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = NfColors.Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF22222B))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Affiches

/**
 * Affiche qui s'élargit en 16:9 (image de fond, titre, infos, synopsis) quand elle a le focus
 * (TV) ou qu'on la touche (téléphone).
 */
@Composable
fun PosterCard(
    item: MediaItem,
    expanded: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    canExpand: Boolean = true,
) {
    val d = LocalDims.current
    val isTv = LocalIsTv.current
    val client = LocalClient.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    val open = expanded && canExpand
    val width by animateDpAsState(
        targetValue = if (open) d.expandedW else d.posterW,
        animationSpec = tween(NfMotion.EXPAND_MS, easing = NfEasing),
        label = "poster-width",
    )
    // Pas de contour : l'affiche qui s'agrandit suffit à montrer la sélection.
    // Celles qui ne s'agrandissent pas (Top 10) grossissent légèrement à la place.
    val lift by animateFloatAsState(
        targetValue = if (focused && !canExpand) 1.07f else 1f,
        animationSpec = tween(NfMotion.EXPAND_MS, easing = NfEasing),
        label = "poster-lift",
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier
            .width(width)
            .height(d.posterH)
            .optionalFocus(focusRequester)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(shape)
            .background(NfColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            item.displayTitle,
            color = NfColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(10.dp),
        )
        AsyncImage(
            model = if (item.external) item.externalPoster else client.imageUrl(item.posterItemId, "Primary", fillHeight = 420),
            contentDescription = item.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.backdropItemId != null || item.externalBackdrop != null) {
            AnimatedVisibility(visible = open, enter = fadeIn(tween(220)), exit = fadeOut(tween(120))) {
                AsyncImage(
                    model = if (item.external) item.externalBackdrop else client.imageUrl(item.backdropItemId, "Backdrop", fillWidth = 800),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(NfMotion.SYNOPSIS_MS, delayMillis = NfMotion.SYNOPSIS_DELAY_MS)) +
                slideInHorizontally(tween(NfMotion.SYNOPSIS_MS, delayMillis = NfMotion.SYNOPSIS_DELAY_MS)) { -it / 25 },
            exit = fadeOut(tween(90)),
        ) {
            ExpandedInfo(item)
        }
        if (!open && item.external) {
            // Titre venu de TMDB qui n'est pas encore dans la bibliothèque.
            Text(
                item.externalBadge ?: "À DÉCOUVRIR",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        if (!open && item.isNew) {
            Text(
                item.newBadgeLabel(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(NfColors.Accent)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        LocalFormatBadges.current[item.id]?.let { FormatBadge(it, Modifier.align(Alignment.TopEnd)) }
        if (item.played && !item.external && !open) PlayedMark(Modifier.align(Alignment.TopStart))
        if (item.inProgress) {
            val fraction = ((item.playedPercentage ?: 0.0) / 100.0).toFloat().coerceIn(0.02f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(NfColors.Accent),
                )
            }
        }
    }
}

/** Déjà vu (série : tous ses épisodes) : petite coche en haut à gauche de l'affiche. */
@Composable
fun PlayedMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) { Icon(NfIcons.Check, contentDescription = "Vu", tint = Color.White, modifier = Modifier.size(14.dp)) }
}

/** Vignette « AV1 » : format que cet appareil ne sait pas lire de façon fluide. */
@Composable
fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Text(
        format,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xE6B3121C))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ExpandedInfo(item: MediaItem) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xF2080810),
                    0.58f to Color(0xD1080810),
                    1f to Color(0x26080810),
                ),
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.9f)
                .padding(start = 14.dp, end = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                item.displayTitle,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.episodeLine() ?: item.metaLine(),
                color = NfColors.TextSoft,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Sur TV le résumé est déjà affiché en grand en haut de l'écran : la carte reste épurée.
            if (!LocalIsTv.current && item.overview.isNotBlank()) {
                Text(
                    item.overview,
                    color = Color(0xFFE4E4E9),
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NfColors.Accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(NfIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(
                        when {
                            item.external -> "Découvrir"
                            item.inProgress -> "Reprendre"
                            else -> "Lecture"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item.asideLabel()?.let {
                    Text(it, color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Élément du Top 10 : grand chiffre en contour derrière l'affiche. */
@Composable
fun TopTenItem(rank: Int, item: MediaItem, onFocused: () -> Unit, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val d = LocalDims.current
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.height(d.posterH)) {
        Text(
            rank.toString(),
            maxLines = 1,
            overflow = TextOverflow.Visible,
            style = TextStyle(
                color = NfColors.RankStroke,
                fontSize = d.rankSize,
                fontWeight = FontWeight.Black,
                letterSpacing = (-6).sp,
                drawStyle = Stroke(width = 5f),
                lineHeight = d.rankSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Bottom,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
        PosterCard(
            item = item,
            expanded = false,
            onFocused = onFocused,
            onClick = onClick,
            canExpand = false,
            focusRequester = focusRequester,
            modifier = Modifier.offset(x = -(d.posterW * 0.22f)),
        )
    }
}

/**
 * Désactive le défilement automatique standard d'une ligne : il visait l'affiche suivante
 * pendant que la précédente rétrécissait encore, ce qui faisait « reculer » la ligne.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Défilement « juste ce qu'il faut » : on ne bouge que si l'élément sélectionné sort de l'écran.
 * (Sur TV, Android remonte sinon chaque élément sélectionné vers le tiers de l'écran.)
 */
@OptIn(ExperimentalFoundationApi::class)
internal val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            offset >= 0f && trailingEdge <= containerSize -> 0f
            offset < 0f && trailingEdge > containerSize -> 0f
            offset < 0f -> offset
            else -> trailingEdge - containerSize
        }
    }
}

/** Une ligne : titre, « Voir plus », puis les affiches qui défilent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRowView(
    row: MediaRow,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onMore: ((MoreLink) -> Unit)?,
    firstFocus: FocusRequester? = null,
    /** Affiche à resélectionner en revenant sur l'écran (clé « ligne:titre ») et son FocusRequester. */
    restoreKey: String? = null,
    restoreFocus: FocusRequester? = null,
) {
    val d = LocalDims.current
    val isTv = LocalIsTv.current
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.rowPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.title, color = Color.White, fontSize = d.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
            // Le sous-titre prend toute la place restante : « Voir plus » reste calé à droite.
            if (row.sub.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    row.sub,
                    color = NfColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            val more = row.more
            if (more != null && onMore != null) FocusText("Voir tout", onClick = { onMore(more) })
        }
        // Défilement « à point fixe » : l'affiche sélectionnée vient se caler au début de la
        // ligne puis s'agrandit vers la droite. Si l'affiche précédente (à gauche) est encore
        // large, on vise la position qu'aura la cible une fois celle-ci rétrécie, et on défile
        // avec la même durée et la même courbe que l'agrandissement : pas de dépassement.
        val restoreIndex = restoreKey?.let { k -> row.items.indexOfFirst { row.id + ":" + it.id == k } }?.takeIf { it >= 0 }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex ?: 0)
        val collapsedPx = with(LocalDensity.current) { d.posterW.toPx() }
        var focusedIndex by remember(row.id) { mutableIntStateOf(-1) }
        var lastIndex by remember(row.id) { mutableIntStateOf(-1) }
        LaunchedEffect(focusedIndex) {
            val target = focusedIndex
            if (target < 0) return@LaunchedEffect
            val previous = lastIndex
            lastIndex = target
            val visible = listState.layoutInfo.visibleItemsInfo
            val targetInfo = visible.firstOrNull { it.index == target }
            if (targetInfo == null) {
                listState.animateScrollToItem(target)
                return@LaunchedEffect
            }
            val previousInfo = visible.firstOrNull { it.index == previous }
            val shrink = if (!row.top && previousInfo != null && previous < target) {
                (previousInfo.size - collapsedPx).coerceAtLeast(0f)
            } else {
                0f
            }
            val delta = targetInfo.offset - shrink
            if (abs(delta) > 0.5f) {
                listState.animateScrollBy(delta, tween(NfMotion.EXPAND_MS, easing = NfEasing))
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = d.rowPad, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (row.top) 2.dp else d.gap),
            ) {
                itemsIndexed(row.items, key = { _, it -> it.id }) { index, item ->
                    val key = row.id + ":" + item.id
                    val requester = when {
                        restoreFocus != null && index == restoreIndex -> restoreFocus
                        index == 0 -> firstFocus
                        else -> null
                    }
                    val focusHere = { focusedIndex = index; onSelect(key) }
                    if (row.top) {
                        TopTenItem(
                            rank = index + 1,
                            item = item,
                            onFocused = focusHere,
                            onClick = { onPlay(item) },
                            focusRequester = requester,
                        )
                    } else {
                        PosterCard(
                            item = item,
                            expanded = key == selectedKey,
                            onFocused = focusHere,
                            // Téléphone : 1er appui = l'affiche vient se caler au bord gauche et s'ouvre ;
                            // 2e appui = ouvre la fiche. TV : OK ouvre directement la fiche.
                            onClick = { if (isTv || key == selectedKey) onPlay(item) else focusHere() },
                            focusRequester = requester,
                        )
                    }
                }
                // Dernière vignette : « Voir tout » (atteignable à la télécommande avec →).
                val moreLink = row.more
                if (moreLink != null && onMore != null && !row.top) {
                    item(key = "see-all") {
                        SeeAllCard(
                            onFocused = {
                                focusedIndex = row.items.size
                                onSelect(row.id + ":see-all")
                            },
                            onClick = { onMore(moreLink) },
                        )
                    }
                }
            }
        }
    }
}

/** Vignette « Voir tout » au bout d'une ligne : ouvre toute la catégorie en grille. */
@Composable
private fun SeeAllCard(onFocused: () -> Unit, onClick: () -> Unit) {
    val d = LocalDims.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val lift by animateFloatAsState(if (focused) 1.07f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "see-all-lift")
    Box(
        Modifier
            .width(d.posterW)
            .height(d.posterH)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.07f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                NfIcons.Back,
                contentDescription = null,
                tint = if (focused) Color.Black else Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer { rotationZ = 180f },
            )
            Text("Voir tout", color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- Bandeau « À la une »

@Composable
fun HeroBanner(item: MediaItem, onPlay: () -> Unit, onToggleFavorite: () -> Unit, onInfo: (() -> Unit)? = null) {
    val d = LocalDims.current
    val isTv = LocalIsTv.current
    val client = LocalClient.current
    var logoFailed by remember(item.id) { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(d.heroH),
    ) {
        AsyncImage(
            model = if (item.backdropItemId != null) {
                client.imageUrl(item.backdropItemId, "Backdrop", fillWidth = 1280)
            } else {
                client.imageUrl(item.posterItemId, "Primary", fillWidth = 900)
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (isTv) {
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.97f),
                            0.4f to Color.Black.copy(alpha = 0.78f),
                            0.78f to Color.Transparent,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.1f),
                            0.45f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black,
                        )
                    },
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color.Black)),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = d.rowPad, end = d.rowPad, bottom = 16.dp)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (item.isNew) "À LA UNE · NOUVEAU" else "À LA UNE",
                color = NfColors.AccentText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (item.logoItemId != null && !logoFailed) {
                AsyncImage(
                    model = client.imageUrl(item.logoItemId, "Logo", fillHeight = 160),
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier
                        .heightIn(max = if (isTv) 70.dp else 64.dp)
                        .widthIn(max = 300.dp),
                )
            } else {
                Text(
                    item.displayTitle,
                    color = Color.White,
                    fontSize = d.titleSize,
                    fontWeight = FontWeight.Bold,
                    lineHeight = d.titleSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                item.ratingLabel()?.let { Text(it, color = NfColors.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Text(item.metaLine(), color = NfColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.overview.isNotBlank()) {
                Text(
                    item.overview,
                    color = Color(0xFFE4E4E9),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = if (isTv) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                NfButton("Lecture", onClick = onPlay, icon = NfIcons.Play, primary = true)
                NfButton(
                    if (item.favorite) "Dans ma liste" else "Ma liste",
                    onClick = onToggleFavorite,
                    icon = if (item.favorite) NfIcons.Check else NfIcons.Plus,
                )
                if (onInfo != null) NfButton("Infos", onClick = onInfo)
            }
        }
    }
}

// ---------------------------------------------------------------- États

@Composable
fun CenterLoader(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NfColors.Accent)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, secondaryLabel: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NfButton("Réessayer", onClick = onRetry, primary = true)
            if (secondaryLabel != null && onSecondary != null) NfButton(secondaryLabel, onClick = onSecondary)
        }
    }
}
