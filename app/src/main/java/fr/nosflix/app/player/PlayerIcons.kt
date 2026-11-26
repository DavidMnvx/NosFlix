package fr.nosflix.app.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icônes au trait des options du lecteur (même style que le reste de l'appli). */
private fun playerIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

private fun PathBuilder.box(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    horizontalLineTo(x + w)
    verticalLineTo(y + h)
    horizontalLineTo(x)
    close()
}

object PlayerIcons {
    val Audio = playerIcon("audio") {
        moveTo(4f, 9f); horizontalLineTo(8f); lineTo(13f, 5f); verticalLineTo(19f); lineTo(8f, 15f); horizontalLineTo(4f); close()
        moveTo(16.5f, 8.5f)
        arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.5f, y1 = 15.5f)
    }
    val Subtitles = playerIcon("subtitles") {
        box(3f, 5f, 18f, 14f)
        moveTo(7f, 12f); horizontalLineTo(11f)
        moveTo(13f, 12f); horizontalLineTo(17f)
        moveTo(7f, 15.5f); horizontalLineTo(14f)
    }
    val Format = playerIcon("format") {
        box(3f, 5f, 18f, 14f)
        moveTo(7f, 11f); verticalLineTo(8.5f); horizontalLineTo(9.5f)
        moveTo(17f, 13f); verticalLineTo(15.5f); horizontalLineTo(14.5f)
    }
    val Speed = playerIcon("speed") {
        moveTo(4f, 17f)
        arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 17f)
        moveTo(12f, 17f); lineTo(16f, 11f)
    }
    val Equalizer = playerIcon("equalizer") {
        moveTo(6f, 4f); verticalLineTo(20f)
        moveTo(12f, 4f); verticalLineTo(20f)
        moveTo(18f, 4f); verticalLineTo(20f)
        moveTo(4f, 14f); horizontalLineTo(8f)
        moveTo(10f, 8f); horizontalLineTo(14f)
        moveTo(16f, 16f); horizontalLineTo(20f)
    }
}
