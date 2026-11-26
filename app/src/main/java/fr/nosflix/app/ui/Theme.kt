package fr.nosflix.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.R
import fr.nosflix.app.data.JellyfinClient

/** Police Inter (Google Fonts, licence SIL OFL 1.1), intégrée à l'appli. */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    Font(R.font.inter_extrabold, FontWeight.Black),
)

/** Tous les styles Material passent en Inter, sans l'espacement large par défaut. */
private val NfTypography: Typography = Typography().let { t ->
    fun TextStyle.nf() = copy(fontFamily = Inter, letterSpacing = 0.sp)
    Typography(
        displayLarge = t.displayLarge.nf(),
        displayMedium = t.displayMedium.nf(),
        displaySmall = t.displaySmall.nf(),
        headlineLarge = t.headlineLarge.nf(),
        headlineMedium = t.headlineMedium.nf(),
        headlineSmall = t.headlineSmall.nf(),
        titleLarge = t.titleLarge.nf(),
        titleMedium = t.titleMedium.nf(),
        titleSmall = t.titleSmall.nf(),
        bodyLarge = t.bodyLarge.nf(),
        bodyMedium = t.bodyMedium.nf(),
        bodySmall = t.bodySmall.nf(),
        labelLarge = t.labelLarge.nf(),
        labelMedium = t.labelMedium.nf(),
        labelSmall = t.labelSmall.nf(),
    )
}

/** Palette sobre : noir pur, un seul rouge d'accent, textes blanc/gris. */
object NfColors {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF111116)
    val Surface2 = Color(0xFF1C1C24)
    val Accent = Color(0xFFE1232E)
    val AccentText = Color(0xFFFF4D57)
    val Text = Color(0xFFF4F4F6)
    val TextSoft = Color(0xFFD4D4DA)
    val Muted = Color(0xFFA1A1AA)
    val Subtle = Color(0xFF8E8E98)
    val Divider = Color(0xFF26262E)
    val RankStroke = Color(0xFF55555F)
}

/** Réglages d'animation validés sur la maquette. */
object NfMotion {
    const val EXPAND_MS = 300
    const val SYNOPSIS_DELAY_MS = 130
    const val SYNOPSIS_MS = 200
}

val NfEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/** Tailles qui changent entre la TV (écran de 960 × 540 dp) et le téléphone. */
data class Dims(
    val posterW: Dp,
    val posterH: Dp,
    val expandedW: Dp,
    val rowPad: Dp,
    val gap: Dp,
    val rowTitle: TextUnit,
    val heroH: Dp,
    val rankSize: TextUnit,
    val titleSize: TextUnit,
)

val TvDims = Dims(
    posterW = 100.dp,
    posterH = 150.dp,
    expandedW = 267.dp,
    rowPad = 32.dp,
    gap = 12.dp,
    rowTitle = 19.sp,
    heroH = 250.dp,
    rankSize = 150.sp,
    titleSize = 38.sp,
)

val PhoneDims = Dims(
    posterW = 112.dp,
    posterH = 168.dp,
    expandedW = 299.dp,
    rowPad = 16.dp,
    gap = 10.dp,
    rowTitle = 20.sp,
    heroH = 280.dp,
    rankSize = 168.sp,
    titleSize = 32.sp,
)

val LocalDims = staticCompositionLocalOf { PhoneDims }
val LocalIsTv = staticCompositionLocalOf { false }
val LocalClient = staticCompositionLocalOf<JellyfinClient> { error("JellyfinClient manquant") }

/** Titres que l'appareil lit mal : id → format (« AV1 »), affiché en vignette sur l'affiche. */
val LocalFormatBadges = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun NosFlixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NfColors.Accent,
            onPrimary = Color.White,
            secondary = NfColors.AccentText,
            background = NfColors.Black,
            onBackground = NfColors.Text,
            surface = NfColors.Surface,
            onSurface = NfColors.Text,
            surfaceVariant = NfColors.Surface2,
            onSurfaceVariant = NfColors.Muted,
        ),
        typography = NfTypography,
        content = content,
    )
}

private val GenrePalette = listOf(
    Color(0xFFFF6A3D), Color(0xFFFFB020), Color(0xFF7BD389), Color(0xFFFFD166),
    Color(0xFF3DD6FF), Color(0xFF9AD9FF), Color(0xFFB48CFF), Color(0xFFFF4D6D),
)

fun genreColor(name: String): Color = GenrePalette[(name.hashCode() and 0x7fffffff) % GenrePalette.size]
