package fr.nosflix.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icônes maison au trait (24 × 24), sans dépendre de la bibliothèque material-icons. */
private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        if (filled) {
            path(fill = SolidColor(Color.White), pathBuilder = block)
        } else {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }
    }.build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
    close()
}

private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    horizontalLineTo(x + w)
    verticalLineTo(y + h)
    horizontalLineTo(x)
    close()
}

object NfIcons {
    val Search = icon("search") {
        circle(11f, 11f, 7f)
        moveTo(20f, 20f); lineTo(16.5f, 16.5f)
    }
    val Home = icon("home") {
        moveTo(3f, 11f); lineTo(12f, 4f); lineTo(21f, 11f)
        moveTo(5f, 10f); verticalLineTo(20f); horizontalLineTo(19f); verticalLineTo(10f)
    }
    val Film = icon("film") {
        rect(3f, 4f, 18f, 16f)
        moveTo(7f, 4f); verticalLineTo(20f)
        moveTo(17f, 4f); verticalLineTo(20f)
        moveTo(3f, 9f); horizontalLineTo(7f)
        moveTo(3f, 15f); horizontalLineTo(7f)
        moveTo(17f, 9f); horizontalLineTo(21f)
        moveTo(17f, 15f); horizontalLineTo(21f)
    }
    val Tv = icon("tv") {
        rect(3f, 6f, 18f, 13f)
        moveTo(8f, 2f); lineTo(12f, 6f); lineTo(16f, 2f)
    }
    val Bookmark = icon("bookmark") {
        moveTo(6f, 3f); horizontalLineTo(18f); verticalLineTo(21f); lineTo(12f, 17f); lineTo(6f, 21f); close()
    }
    val Headphones = icon("headphones") {
        moveTo(4f, 14f); verticalLineTo(12f)
        arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 12f)
        verticalLineTo(14f)
        rect(3f, 14f, 5f, 7f)
        rect(16f, 14f, 5f, 7f)
    }
    val Music = icon("music") {
        moveTo(9f, 18f); verticalLineTo(5f); lineTo(21f, 3f); verticalLineTo(16f)
        circle(6f, 18f, 3f)
        circle(18f, 16f, 3f)
    }
    val Settings = icon("settings") {
        moveTo(4f, 6f); horizontalLineTo(13f)
        moveTo(17f, 6f); horizontalLineTo(20f)
        moveTo(4f, 12f); horizontalLineTo(7f)
        moveTo(11f, 12f); horizontalLineTo(20f)
        moveTo(4f, 18f); horizontalLineTo(15f)
        moveTo(19f, 18f); horizontalLineTo(20f)
        circle(15f, 6f, 2f)
        circle(9f, 12f, 2f)
        circle(17f, 18f, 2f)
    }
    val Play = icon("play", filled = true) {
        moveTo(8f, 5.5f); verticalLineTo(18.5f); lineTo(18.5f, 12f); close()
    }
    val Pause = icon("pause", filled = true) {
        rect(6f, 5f, 4f, 14f)
        rect(14f, 5f, 4f, 14f)
    }
    val Plus = icon("plus") {
        moveTo(12f, 5f); verticalLineTo(19f)
        moveTo(5f, 12f); horizontalLineTo(19f)
    }
    val Check = icon("check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }
    val Menu = icon("menu") {
        moveTo(4f, 7f); horizontalLineTo(20f)
        moveTo(4f, 12f); horizontalLineTo(20f)
        moveTo(4f, 17f); horizontalLineTo(20f)
    }
    val Close = icon("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Eye = icon("eye") {
        moveTo(2f, 12f)
        curveTo(4.5f, 7f, 8f, 5f, 12f, 5f)
        curveTo(16f, 5f, 19.5f, 7f, 22f, 12f)
        curveTo(19.5f, 17f, 16f, 19f, 12f, 19f)
        curveTo(8f, 19f, 4.5f, 17f, 2f, 12f)
        close()
        circle(12f, 12f, 3f)
    }
    val EyeOff = icon("eye-off") {
        moveTo(2f, 12f)
        curveTo(4.5f, 7f, 8f, 5f, 12f, 5f)
        curveTo(16f, 5f, 19.5f, 7f, 22f, 12f)
        curveTo(19.5f, 17f, 16f, 19f, 12f, 19f)
        curveTo(8f, 19f, 4.5f, 17f, 2f, 12f)
        close()
        circle(12f, 12f, 3f)
        moveTo(4f, 4f); lineTo(20f, 20f)
    }
    val Back = icon("back") {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }
    val External = icon("external") {
        moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f)
        moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(18f, 14f); verticalLineTo(20f); horizontalLineTo(4f); verticalLineTo(6f); horizontalLineTo(10f)
    }
    /** Actualité : courbe qui monte. */
    val Trending = icon("trending") {
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
        moveTo(15f, 7f); horizontalLineTo(21f); verticalLineTo(13f)
    }
    val User = icon("user") {
        circle(12f, 8f, 4f)
        moveTo(4f, 21f)
        curveTo(5.5f, 17f, 8.5f, 15f, 12f, 15f)
        curveTo(15.5f, 15f, 18.5f, 17f, 20f, 21f)
    }
}
