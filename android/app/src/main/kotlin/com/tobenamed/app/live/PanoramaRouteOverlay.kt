package com.tobenamed.app.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobenamed.app.NavIcon
import com.tobenamed.app.NavigationIcon
import com.tobenamed.nav.geo.Geo
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class ProjectedPatch(val vertices: List<Offset>, val opacity: Float = 1f)
internal data class ProjectedBuilding(val anchor: Offset, val approximate: Boolean)
internal data class ProjectedStreetScene(
    val ribbon: List<ProjectedPatch> = emptyList(),
    val building: ProjectedBuilding? = null,
    val directionOffsetDeg: Float? = null,
    val routeProgressM: Double? = null,
)

/** The trail follows projected geometry; the primary arrow keeps a readable screen size. */
@Composable
internal fun PanoramaRouteOverlay(scene: ProjectedStreetScene, number: String, topInsetPx: Int, revision: Int) {
    val ink = Color(0xFF101C1B)
    val route = Color(0xFFBCF477)
    val buildingColor = Color(0xFFFFD28E)
    val fade = remember { Animatable(1f) }
    LaunchedEffect(revision) {
        if (revision > 0) { fade.snapTo(.35f); fade.animateTo(1f, tween(220)) }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val bottomInset = with(density) { 48.dp.toPx() }
    val labelLift = with(density) { 60.dp.toPx() }
    val margin = with(density) { 10.dp.toPx() }
    var labelWidth by remember { mutableIntStateOf(with(density) { 90.dp.roundToPx() }) }
    val marker = scene.building?.takeIf { marker ->
        marker.anchor.x in margin..(viewport.width - margin) &&
            marker.anchor.y in (topInsetPx + labelLift + margin)..(viewport.height - bottomInset)
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            fun outline(patch: ProjectedPatch) = Path().apply {
                moveTo(patch.vertices[0].x, patch.vertices[0].y)
                patch.vertices.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            clipRect(top = topInsetPx.toFloat().coerceAtMost(size.height), bottom = (size.height - bottomInset).coerceAtLeast(topInsetPx.toFloat())) {
                scene.ribbon.forEach { patch -> drawPath(outline(patch), route.copy(alpha = .25f * patch.opacity)) }
                marker?.let {
                    drawLine(buildingColor, it.anchor - Offset(0f, 20.dp.toPx()), it.anchor, 2.dp.toPx())
                    drawCircle(ink, 5.dp.toPx(), it.anchor)
                    drawCircle(buildingColor, 3.dp.toPx(), it.anchor)
                }
            }
        }
        scene.directionOffsetDeg?.let { direction ->
            val angle = remember { Animatable(direction) }
            val distance = remember { Animatable((scene.routeProgressM ?: 0.0).toFloat()) }
            LaunchedEffect(direction) {
                angle.animateTo(angle.value + Geo.angleDiff(direction.toDouble(), angle.value.toDouble()).toFloat(), tween(280))
            }
            LaunchedEffect(scene.routeProgressM) {
                distance.animateTo((scene.routeProgressM ?: 0.0).toFloat(), tween(700, easing = LinearEasing))
            }
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "Walking route direction" }) {
                // Reserve room for Follow walk / View building and native attribution.
                val placement = DirectionArrowLayout.place(size.width, size.height, topInsetPx.toFloat(), 140.dp.toPx(), density.density)
                    ?: return@Canvas
                val center = Offset(placement.centerX, placement.centerY)
                val diameter = placement.diameter
                // Advance from measured walking progress, never from a repeating animation timer.
                val phase = ((distance.value / 3f) % 1f + 1f) % 1f
                rotate(angle.value, center) {
                    repeat(3) { index ->
                        val depth = (index + phase) / 3f
                        val halfWidth = diameter * (.38f - .18f * depth)
                        val height = diameter * (.18f - .08f * depth)
                        val y = center.y + diameter * (.21f - .58f * depth)
                        val thickness = height * .42f
                        val arrow = Path().apply {
                            moveTo(center.x, y - height / 2)
                            lineTo(center.x + halfWidth, y + height / 2)
                            lineTo(center.x + halfWidth - thickness * 1.6f, y + height / 2)
                            lineTo(center.x, y - height / 2 + thickness)
                            lineTo(center.x - halfWidth + thickness * 1.6f, y + height / 2)
                            lineTo(center.x - halfWidth, y + height / 2)
                            close()
                        }
                        val opacity = .4f + .6f * sin(depth * Math.PI).toFloat()
                        drawPath(arrow, ink.copy(alpha = opacity), style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                        drawPath(arrow, route.copy(alpha = opacity))
                        drawPath(arrow, Color.White.copy(alpha = .65f * opacity), style = Stroke(.7.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
            }
        }
        marker?.let { building ->
            val label = if (building.approximate) "Area" else number.ifBlank { "Building" }
            Surface(color = buildingColor, contentColor = ink, shape = RoundedCornerShape(12.dp), shadowElevation = 5.dp,
                modifier = Modifier.graphicsLayer { alpha = fade.value }.offset {
                    IntOffset((building.anchor.x - labelWidth / 2f).coerceIn(margin,
                        (viewport.width - labelWidth - margin).coerceAtLeast(margin)).roundToInt(),
                        (building.anchor.y - labelLift).roundToInt())
                }.onSizeChanged { labelWidth = it.width }.clearAndSetSemantics {
                    contentDescription = if (building.approximate) "Approximate building area" else "Destination building $label"
                }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    NavigationIcon(NavIcon.HOUSE, ink, Modifier.size(20.dp))
                    Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
