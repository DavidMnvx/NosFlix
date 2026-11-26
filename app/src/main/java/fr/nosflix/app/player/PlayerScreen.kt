package fr.nosflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fr.nosflix.app.ui.NfButton
import fr.nosflix.app.ui.NfColors
import fr.nosflix.app.ui.NfIcons
import fr.nosflix.app.ui.NfLogoMark
import fr.nosflix.app.ui.formatTime
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

private val ToolbarPanels = listOf(
    PlayerPanel.Audio to PlayerIcons.Audio,
    PlayerPanel.Subtitles to PlayerIcons.Subtitles,
    PlayerPanel.Video to PlayerIcons.Format,
    PlayerPanel.Speed to PlayerIcons.Speed,
    PlayerPanel.Equalizer to PlayerIcons.Equalizer,
)

@Composable
fun PlayerScreen(state: PlayerUiState, actions: PlayerActions, onAttach: (VLCVideoLayout) -> Unit) {
    val focusManager = LocalFocusManager.current
    val panelRequesters = remember { PlayerPanel.entries.associateWith { FocusRequester() } }
    // Dernier menu ouvert : on y remet le focus en refermant, et il reste affiché pendant l'animation de sortie.
    var lastPanel by remember { mutableStateOf(PlayerPanel.Audio) }
    LaunchedEffect(state.panel) { state.panel?.let { lastPanel = it } }
    val shownPanel = state.panel ?: lastPanel

    // Les commandes disparaissent après 4 s de lecture sans action (jamais pendant qu'un menu est ouvert).
    LaunchedEffect(state.poke, state.playing, state.toolbarActive, state.panel) {
        if (state.playing && state.controlsVisible && !state.toolbarActive && state.panel == null) {
            delay(4_000)
            state.controlsVisible = false
        }
    }

    // Focus de la télécommande : sur le bouton du dernier menu utilisé, ou plus nulle part.
    LaunchedEffect(state.toolbarActive, state.panel) {
        if (state.panel == null) {
            if (state.toolbarActive) {
                delay(40)
                runCatching { panelRequesters.getValue(lastPanel).requestFocus() }
            } else {
                focusManager.clearFocus()
            }
        }
    }

    // Retour : ferme d'abord le menu ouvert, puis masque les commandes ; seulement ensuite on quitte la vidéo.
    BackHandler(enabled = state.panel != null) { actions.openPanel(null) }
    BackHandler(enabled = state.panel == null && state.toolbarActive) {
        state.toolbarActive = false
        state.controlsVisible = false
    }
    BackHandler(enabled = state.panel == null && !state.toolbarActive && state.controlsVisible && state.error == null) {
        state.controlsVisible = false
    }
    // Proposition « Épisode suivant » à l'écran : Retour la refuse.
    BackHandler(enabled = state.nextCountdown >= 0 && state.panel == null) { actions.cancelNext() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    when {
                        state.panel != null -> actions.openPanel(null)
                        state.controlsVisible -> state.controlsVisible = false
                        else -> state.showControls()
                    }
                }
            },
    ) {
        AndroidView(
            factory = { context -> VLCVideoLayout(context).also { view -> view.post { onAttach(view) } } },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.buffering && state.error == null) {
            CircularProgressIndicator(
                color = NfColors.Accent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        AnimatedVisibility(
            visible = (state.controlsVisible || state.panel != null) && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(state, actions, panelRequesters)
        }
        AnimatedVisibility(
            visible = state.panel != null && state.error == null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            OptionsPanel(shownPanel, state, actions)
        }
        // « Passer l'intro » et « Épisode suivant », en bas à droite.
        val skip = state.skipLabel
        if (skip != null && state.nextCountdown < 0 && state.panel == null && state.error == null) {
            SkipButton(skip, actions, Modifier.align(Alignment.BottomEnd))
        }
        val nextLabel = state.nextLabel
        if (state.nextCountdown >= 0 && nextLabel != null && state.panel == null && state.error == null) {
            NextEpisodeCard(nextLabel, state.nextCountdown, actions, Modifier.align(Alignment.BottomEnd))
        }
        state.error?.let { PlayerError(it, actions) }
    }
}

@Composable
private fun SkipButton(label: String, actions: PlayerActions, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Box(modifier.padding(end = 40.dp, bottom = 150.dp)) {
        NfButton("$label  ›", onClick = actions::skipSegment, focusRequester = focus)
    }
    // Sur TV : le bouton prend la main, un appui sur OK suffit.
    LaunchedEffect(label) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun NextEpisodeCard(label: String, seconds: Int, actions: PlayerActions, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier
            .padding(end = 40.dp, bottom = 120.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xEB111116))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ÉPISODE SUIVANT", color = NfColors.AccentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            NfButton("Lecture dans $seconds s", onClick = actions::playNextNow, icon = NfIcons.Play, primary = true, focusRequester = focus)
            NfButton("Annuler", onClick = actions::cancelNext)
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

// ---------------------------------------------------------------- Commandes

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    actions: PlayerActions,
    panelRequesters: Map<PlayerPanel, FocusRequester>,
) {
    // Le curseur et les gros boutons ne prennent pas le focus : la télécommande est gérée par l'activité.
    val noFocus = Modifier.focusProperties { canFocus = false }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val length = state.lengthMs.coerceAtLeast(1L)
    val fraction = scrub ?: (state.timeMs.toFloat() / length).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(230.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                noFocus
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = actions::close),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NfIcons.Back, contentDescription = "Retour", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(state.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.subtitle.isNotBlank()) {
                    Text(state.subtitle, color = NfColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            NfLogoMark(36.dp)
        }
        Box(
            noFocus
                .align(Alignment.Center)
                .size(76.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable(onClick = actions::togglePause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.playing) NfIcons.Pause else NfIcons.Play,
                contentDescription = if (state.playing) "Pause" else "Lecture",
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Slider(
                value = fraction,
                onValueChange = {
                    scrub = it
                    state.showControls()
                },
                onValueChangeFinished = {
                    scrub?.let { actions.seekTo((it * length).toLong()) }
                    scrub = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = NfColors.Accent,
                    activeTrackColor = NfColors.Accent,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = noFocus.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTime((fraction * length).toLong())} / ${formatTime(state.lengthMs)}",
                    color = Color.White,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.toolbarActive) "↑ : revenir · OK : ouvrir" else "OK : pause · ◀ −10 s · ▶ +30 s · ▼ : options",
                    color = NfColors.Muted,
                    fontSize = 11.sp,
                )
            }
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolbarPanels.forEach { (panel, icon) ->
                    ToolButton(
                        label = panel.short,
                        icon = icon,
                        focusEnabled = state.toolbarActive,
                        active = state.panel == panel,
                        requester = panelRequesters.getValue(panel),
                        onClick = { actions.openPanel(panel) },
                    )
                }
                ToolButton(
                    label = "Lire avec…",
                    icon = NfIcons.External,
                    focusEnabled = state.toolbarActive,
                    active = false,
                    requester = null,
                    onClick = actions::openExternal,
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: ImageVector,
    focusEnabled: Boolean,
    active: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> Color.White
        active -> NfColors.Accent
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = if (focused) Color.Black else Color.White
    Row(
        Modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties { canFocus = focusEnabled }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Panneau d'options

@Composable
private fun OptionsPanel(panel: PlayerPanel, state: PlayerUiState, actions: PlayerActions) {
    val target = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) {
        delay(60)
        runCatching { target.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .background(Color(0xF20E0E12))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(panel.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .focusProperties { canFocus = false }
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { actions.openPanel(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(NfIcons.Close, contentDescription = "Fermer", tint = NfColors.TextSoft, modifier = Modifier.size(22.dp))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (panel) {
                PlayerPanel.Audio -> AudioOptions(state, actions, target)
                PlayerPanel.Subtitles -> SubtitleOptions(state, actions, target)
                PlayerPanel.Video -> VideoOptions(state, actions, target)
                PlayerPanel.Speed -> SpeedOptions(state, actions, target)
                PlayerPanel.Equalizer -> EqualizerOptions(state, actions, target)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AudioOptions(state: PlayerUiState, actions: PlayerActions, target: FocusRequester) {
    SectionTitle("Piste audio")
    if (state.audioTracks.isEmpty()) Hint("Aucune piste audio détectée pour l’instant.")
    val selected = state.audioTracks.firstOrNull { it.id == state.audioTrack } ?: state.audioTracks.firstOrNull()
    state.audioTracks.forEach { track ->
        OptionRow(
            label = track.name,
            selected = track.id == state.audioTrack,
            requester = if (track == selected) target else null,
            onClick = { actions.selectAudio(track.id) },
        )
    }
    SectionTitle("Synchronisation du son")
    StepperRow(
        label = "Décalage",
        value = delayLabel(state.audioDelayMs),
        onMinus = { actions.shiftAudioDelay(-50) },
        onPlus = { actions.shiftAudioDelay(50) },
        requester = if (selected == null) target else null,
    )
    if (state.audioDelayMs != 0L) OptionRow("Remettre à 0", selected = false, onClick = actions::resetAudioDelay)
    Hint("Le son arrive avant l’image ? Appuie sur +. Il arrive après ? Appuie sur −.")
}

@Composable
private fun SubtitleOptions(state: PlayerUiState, actions: PlayerActions, target: FocusRequester) {
    SectionTitle("Piste de sous-titres")
    if (state.subtitleTracks.isEmpty()) Hint("Aucun sous-titre dans ce fichier ni sur le serveur.")
    val selected = state.subtitleTracks.firstOrNull { it.id == state.subtitleTrack } ?: state.subtitleTracks.firstOrNull()
    state.subtitleTracks.forEach { track ->
        OptionRow(
            label = if (track.id == -1) "Désactivés" else track.name,
            selected = track.id == state.subtitleTrack,
            requester = if (track == selected) target else null,
            onClick = { actions.selectSubtitle(track.id) },
        )
    }
    SectionTitle("Synchronisation des sous-titres")
    StepperRow(
        label = "Décalage",
        value = delayLabel(state.subtitleDelayMs),
        onMinus = { actions.shiftSubtitleDelay(-100) },
        onPlus = { actions.shiftSubtitleDelay(100) },
        requester = if (selected == null) target else null,
    )
    if (state.subtitleDelayMs != 0L) OptionRow("Remettre à 0", selected = false, onClick = actions::resetSubtitleDelay)
    Hint("Les sous-titres arrivent trop tôt ? Appuie sur +. Trop tard ? Appuie sur −.")
}

@Composable
private fun VideoOptions(state: PlayerUiState, actions: PlayerActions, target: FocusRequester) {
    SectionTitle("Taille de l’image")
    VideoFormat.entries.forEach { format ->
        OptionRow(
            label = format.label,
            selected = format == state.format,
            requester = if (format == state.format) target else null,
            onClick = { actions.setFormat(format) },
        )
    }
    Hint("Ce réglage est gardé pour les prochaines vidéos.")
}

@Composable
private fun SpeedOptions(state: PlayerUiState, actions: PlayerActions, target: FocusRequester) {
    val current = PlayerSpeeds.minByOrNull { abs(it - state.rate) } ?: 1f
    PlayerSpeeds.forEach { speed ->
        OptionRow(
            label = speedLabel(speed),
            selected = speed == current,
            requester = if (speed == current) target else null,
            onClick = { actions.setRate(speed) },
        )
    }
}

@Composable
private fun EqualizerOptions(state: PlayerUiState, actions: PlayerActions, target: FocusRequester) {
    OptionRow(
        label = "Désactivé",
        selected = state.eqPreset == EQ_OFF,
        requester = if (state.eqPreset < 0) target else null,
        onClick = { actions.setEqPreset(EQ_OFF) },
    )
    SectionTitle("Préréglages")
    state.eqPresetNames.forEachIndexed { index, name ->
        OptionRow(
            label = name,
            selected = state.eqPreset == index,
            requester = if (state.eqPreset == index) target else null,
            onClick = { actions.setEqPreset(index) },
        )
    }
    SectionTitle(if (state.eqPreset == EQ_CUSTOM) "Réglage manuel (personnalisé)" else "Réglage manuel")
    StepperRow(
        label = "Préampli",
        value = dbLabel(state.eqPreamp),
        onMinus = { actions.shiftEqPreamp(-1f) },
        onPlus = { actions.shiftEqPreamp(1f) },
    )
    state.eqFrequencies.forEachIndexed { band, hz ->
        StepperRow(
            label = frequencyLabel(hz),
            value = dbLabel(state.eqBands.getOrElse(band) { 0f }),
            onMinus = { actions.shiftEqBand(band, -1f) },
            onPlus = { actions.shiftEqBand(band, 1f) },
            level = state.eqBands.getOrElse(band) { 0f } / 20f,
        )
    }
    Hint("Toucher une bande active le réglage personnalisé. L’égaliseur est gardé pour les prochaines vidéos.")
}

// ---------------------------------------------------------------- Éléments du panneau

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = NfColors.Subtle,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = NfColors.Muted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, requester: FocusRequester? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> NfColors.TextSoft
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                NfIcons.Check,
                contentDescription = "Sélectionné",
                tint = if (focused) NfColors.Accent else NfColors.AccentText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Ligne avec − valeur + (décalages, bandes de l'égaliseur). [level] de −1 à 1 dessine une petite jauge. */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    requester: FocusRequester? = null,
    level: Float? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = NfColors.TextSoft, fontSize = 14.sp, maxLines = 1)
            if (level != null) LevelBar(level)
        }
        StepButton("−", onMinus, requester)
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(84.dp),
        )
        StepButton("+", onPlus, null)
    }
}

@Composable
private fun LevelBar(level: Float) {
    val clamped = level.coerceIn(-1f, 1f)
    Box(
        Modifier
            .padding(top = 4.dp, end = 12.dp)
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .align(if (clamped >= 0f) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxWidth(abs(clamped).coerceAtLeast(0.02f))
                .fillMaxHeight()
                .background(if (clamped >= 0f) NfColors.Accent else NfColors.Muted),
        )
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit, requester: FocusRequester?) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .size(38.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (focused) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Erreur

@Composable
private fun PlayerError(message: String, actions: PlayerActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(message, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(
                "Essaie avec un autre lecteur installé sur l’appareil (MX Player, GOM Player, VLC…).",
                color = NfColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NfButton("Lire avec…", onClick = actions::openExternal, icon = NfIcons.External, primary = true, focusRequester = focus)
                NfButton("Retour", onClick = actions::close)
            }
        }
    }
}
