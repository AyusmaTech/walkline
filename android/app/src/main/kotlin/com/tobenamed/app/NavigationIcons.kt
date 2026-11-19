package com.tobenamed.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.tobenamed.nav.live.ManeuverCue

enum class NavIcon {
    BACK, MAP, STREET_VIEW, HOUSE, FOLLOW, DETAILS, CLOSE, LOCATION,
    STRAIGHT, LEFT, RIGHT, BEAR_LEFT, BEAR_RIGHT, U_TURN, ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT,
    PAUSE, WARNING, CHECK, JOIN;

    companion object {
        fun forManeuver(cue: ManeuverCue): NavIcon = when (cue) {
            ManeuverCue.STRAIGHT -> STRAIGHT
            ManeuverCue.LEFT -> LEFT
            ManeuverCue.RIGHT -> RIGHT
            ManeuverCue.BEAR_LEFT -> BEAR_LEFT
            ManeuverCue.BEAR_RIGHT -> BEAR_RIGHT
            ManeuverCue.U_TURN -> U_TURN
            ManeuverCue.ROUNDABOUT_LEFT -> ROUNDABOUT_LEFT
            ManeuverCue.ROUNDABOUT_RIGHT -> ROUNDABOUT_RIGHT
            ManeuverCue.FOLLOW_ROUTE -> FOLLOW
        }
    }
}

/** Shared vector geometry. Icons in labelled controls are decorative to accessibility services. */
@Composable
fun NavigationIcon(icon: NavIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawNavigationIcon(icon, tint)
        }
    }
}

private fun DrawScope.drawNavigationIcon(icon: NavIcon, tint: Color) {
    val stroke = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = stroke)
    when (icon) {
        NavIcon.BACK -> line { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
        NavIcon.MAP -> {
            line { moveTo(3f, 5f); lineTo(9f, 3f); lineTo(15f, 5f); lineTo(21f, 3f); lineTo(21f, 19f)
                lineTo(15f, 21f); lineTo(9f, 19f); lineTo(3f, 21f); close() }
            line { moveTo(9f, 3f); lineTo(9f, 19f); moveTo(15f, 5f); lineTo(15f, 21f) }
        }
        NavIcon.STREET_VIEW -> {
            line { moveTo(3f, 5f); quadraticTo(12f, 8f, 21f, 5f); lineTo(21f, 19f)
                quadraticTo(12f, 16f, 3f, 19f); close() }
            line { moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(20f, 16f) }
        }
        NavIcon.HOUSE -> {
            line { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f) }
            line { moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 15f); lineTo(14f, 15f)
                lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f) }
        }
        NavIcon.FOLLOW -> {
            line { moveTo(12f, 3f); lineTo(20f, 21f); lineTo(12f, 17f); lineTo(4f, 21f); close() }
        }
        NavIcon.DETAILS -> for (x in listOf(5f, 12f, 19f)) drawCircle(tint, 1.5f, Offset(x, 12f))
        NavIcon.CLOSE -> line { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
        NavIcon.LOCATION -> {
            drawCircle(tint, 6f, Offset(12f, 12f), style = stroke)
            drawCircle(tint, 1.8f, Offset(12f, 12f))
            line { moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f)
                moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f) }
        }
        NavIcon.STRAIGHT -> line { moveTo(12f, 21f); lineTo(12f, 3f); moveTo(6f, 9f); lineTo(12f, 3f); lineTo(18f, 9f) }
        NavIcon.LEFT, NavIcon.RIGHT -> {
            scale(if (icon == NavIcon.RIGHT) -1f else 1f, 1f, Offset(12f, 12f)) {
                line { moveTo(14f, 21f); lineTo(14f, 12f); quadraticTo(14f, 7f, 9f, 7f); lineTo(3f, 7f)
                    moveTo(8f, 2f); lineTo(3f, 7f); lineTo(8f, 12f) }
            }
        }
        NavIcon.BEAR_LEFT, NavIcon.BEAR_RIGHT, NavIcon.JOIN -> {
            scale(if (icon == NavIcon.BEAR_RIGHT || icon == NavIcon.JOIN) -1f else 1f, 1f, Offset(12f, 12f)) {
                line { moveTo(14f, 21f); lineTo(14f, 14f); quadraticTo(14f, 11f, 11f, 8f); lineTo(5f, 3f)
                    moveTo(5f, 10f); lineTo(5f, 3f); lineTo(12f, 3f) }
            }
        }
        NavIcon.U_TURN -> line { moveTo(18f, 21f); lineTo(18f, 9f); cubicTo(18f, 1f, 6f, 1f, 6f, 9f)
            lineTo(6f, 14f); moveTo(2f, 10f); lineTo(6f, 14f); lineTo(10f, 10f) }
        NavIcon.ROUNDABOUT_LEFT, NavIcon.ROUNDABOUT_RIGHT -> {
            scale(if (icon == NavIcon.ROUNDABOUT_LEFT) -1f else 1f, 1f, Offset(12f, 12f)) {
                line { moveTo(12f, 22f); lineTo(12f, 18f); cubicTo(2f, 18f, 4f, 6f, 11f, 6f)
                    quadraticTo(17f, 6f, 17f, 12f); lineTo(22f, 12f)
                    moveTo(18f, 8f); lineTo(22f, 12f); lineTo(18f, 16f) }
            }
        }
        NavIcon.PAUSE -> line { moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f) }
        NavIcon.WARNING -> {
            line { moveTo(12f, 3f); lineTo(22f, 20f); lineTo(2f, 20f); close(); moveTo(12f, 9f); lineTo(12f, 13f) }
            drawCircle(tint, .8f, Offset(12f, 16.5f))
        }
        NavIcon.CHECK -> line { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
    }
}
