package fr.nosflix.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.nosflix.app.BuildConfig
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Lettres « NF » dessinées en polygones, dans une boîte de 48 × 40 unités. */
private val NfPath: Path = Path().apply {
    // N
    moveTo(0f, 40f); lineTo(0f, 0f); lineTo(8f, 0f); lineTo(18f, 24f); lineTo(18f, 0f)
    lineTo(26f, 0f); lineTo(26f, 40f); lineTo(18f, 40f); lineTo(8f, 16f); lineTo(8f, 40f); close()
    // F
    moveTo(30f, 40f); lineTo(30f, 0f); lineTo(48f, 0f); lineTo(48f, 7f); lineTo(38f, 7f)
    lineTo(38f, 17f); lineTo(46f, 17f); lineTo(46f, 24f); lineTo(38f, 24f); lineTo(38f, 40f); close()
}

private val NfBrush = Brush.verticalGradient(
    0f to Color(0xFFFF6B5E),
    0.5f to Color(0xFFE1232E),
    1f to Color(0xFF6E0812),
    startY = 0f,
    endY = 40f,
)

/**
 * Logo en dégradé rouge : « NF » (version perso) ou le mot « nosflix » (version publique).
 * [sheen] (0 → 1) fait passer un reflet blanc sur les lettres.
 */
@Composable
fun NfLetters(modifier: Modifier = Modifier, sheen: Float? = null) {
    if (!BuildConfig.MONOGRAM) {
        Wordmark(modifier, listOf("nosflix"), sheen)
        return
    }
    Canvas(modifier) {
        val s = min(size.width / 48f, size.height / 40f)
        val dx = (size.width - 48f * s) / 2f
        val dy = (size.height - 40f * s) / 2f
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(NfPath, NfBrush)
                if (sheen != null) {
                    val x = -16f + sheen * 80f
                    drawPath(
                        NfPath,
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                            start = Offset(x - 12f, 0f),
                            end = Offset(x + 12f, 40f),
                        ),
                    )
                }
            }
        }
    }
}

/** Logo carré sur fond noir : NF rouge (version perso), ou « nos / flix » sur deux lignes (version publique). */
@Composable
fun NfLogoMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.25f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color(0xFF3A0D12), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (BuildConfig.MONOGRAM) {
            NfLetters(Modifier.size(size * 0.6f))
        } else {
            Wordmark(Modifier.size(size * 0.78f), listOf("nos", "flix"), sheen = null)
        }
    }
}

/**
 * Le mot « nosflix » en lettres grasses et dégradé rouge, aussi grand que la boîte le permet.
 * [lines] : une ligne (« nosflix ») ou deux (« nos », « flix ») pour les petits carrés.
 */
@Composable
private fun Wordmark(modifier: Modifier, lines: List<String>, sheen: Float?) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val reference = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 100.sp, letterSpacing = (-0.04).em)
        val probe = lines.map { measurer.measure(it, reference) }
        // Lignes serrées : 82 % de la hauteur d'une ligne de texte.
        val step = 0.82f
        val refWidth = probe.maxOf { it.size.width }.toFloat()
        val refHeight = probe.first().size.height * (1f + step * (lines.size - 1))
        val scale = min(size.width / refWidth, size.height / refHeight)
        val style = reference.copy(fontSize = (100f * scale).sp)
        val layouts = lines.map { measurer.measure(it, style) }
        val lineHeight = layouts.first().size.height.toFloat()
        val total = lineHeight * (1f + step * (lines.size - 1))
        val top = (size.height - total) / 2f
        val red = Brush.verticalGradient(
            0f to Color(0xFFFF6B5E),
            0.5f to Color(0xFFE1232E),
            1f to Color(0xFF6E0812),
            startY = top,
            endY = top + total,
        )
        layouts.forEachIndexed { i, layout ->
            val origin = Offset((size.width - layout.size.width) / 2f, top + i * lineHeight * step)
            drawText(layout, brush = red, topLeft = origin)
            if (sheen != null) {
                val x = -0.2f * size.width + sheen * 1.4f * size.width
                drawText(
                    layout,
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.85f),
                        1f to Color.Transparent,
                        start = Offset(x - size.width * 0.12f, 0f),
                        end = Offset(x + size.width * 0.12f, size.height),
                    ),
                    topLeft = origin,
                )
            }
        }
    }
}
