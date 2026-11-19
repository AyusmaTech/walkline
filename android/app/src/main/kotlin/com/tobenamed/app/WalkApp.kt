package com.tobenamed.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobenamed.nav.route.RouteStatus
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.street.RelativeSide
import com.tobenamed.nav.street.SideGuidance
import kotlin.math.roundToInt

private val Ink = Color(0xFF101C1B)
private val Panel = Color(0xFF1D302D)
private val Lime = Color(0xFFBCF477)
private val Muted = Color(0xFFA8BCB4)
private val Cream = Color(0xFFF2F4E9)
private val Amber = Color(0xFFFFCD87)

enum class WalkCue { NONE, TURN, TURN_AROUND, CHECK_SIDE, ARRIVED }

@Composable
fun WalkApp(
    state: WalkUiState,
    onAddressChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onReview: () -> Unit,
    onDemo: () -> Unit,
    onStart: () -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onCross: () -> Unit,
    onBack: () -> Unit,
    onCue: (WalkCue) -> Unit,
    onDetailsChanged: (String) -> Unit,
    onResolve: () -> Unit,
    onChoose: (AddressCandidate) -> Unit,
    onLocation: () -> Unit,
    onLocationSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onStartLive: () -> Unit,
    onSelectStreet: (LatLng) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Lime, onPrimary = Ink, background = Ink, surface = Panel,
        onSurface = Cream, onBackground = Cream, secondary = Muted,
    )) {
        val scrollState = rememberScrollState()
        LaunchedEffect(state.screen) { scrollState.scrollTo(0) }
        BackHandler(enabled = state.screen != Screen.INPUT, onBack = onBack)
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            if (state.screen == Screen.LIVE_PREVIEW || state.screen == Screen.LIVE_WALK) {
                StreetNavigationScreen(state, onLocation, onLocationSettings, onAppSettings, onStartLive, onBack, onCue, onSelectStreet)
            } else Column(
                Modifier.safeDrawingPadding().verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("↗", color = Lime, fontSize = 30.sp, modifier = Modifier.semantics { contentDescription = "" })
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                    if (state.screen != Screen.INPUT) TextButton(onClick = onBack) { Text("Back") }
                    else Tag("EARLY BUILD")
                }

                when (state.screen) {
                    Screen.INPUT -> InputScreen(state, onAddressChanged, onDetailsChanged, onPaste, onReview, onDemo,
                        onLocation, onLocationSettings, onAppSettings)
                    Screen.ADDRESS -> AddressReviewScreen(state, onAddressChanged, onDetailsChanged, onResolve, onBack, onDemo)
                    Screen.PREVIEW -> PreviewScreen(onStart)
                    Screen.WALK -> state.walk?.let { WalkScreen(it, onForward, onBackward, onCross, onStart, onCue) }
                    Screen.MATCHES -> AddressMatchesScreen(state, onChoose)
                    Screen.LIVE_PREVIEW, Screen.LIVE_WALK -> Unit
                }
            }
        }
    }
}

@Composable
private fun InputScreen(
    state: WalkUiState,
    onAddressChanged: (String) -> Unit,
    onDetailsChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onReview: () -> Unit,
    onDemo: () -> Unit,
    onLocation: () -> Unit,
    onLocationSettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    Eyebrow("THE LAST 100 METRES")
    Text("See the street.\nFind your way.", fontSize = 40.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
    Text("Walking directions over a full-screen Street View scene.", color = Muted, fontSize = 17.sp)
    PanelCard {
        Text("Where are you going?", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
        OutlinedTextField(
            value = state.address, onValueChange = onAddressChanged,
            label = { Text("Destination address") },
            placeholder = { Text("Street address, apartment or unit") },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            isError = state.inputError != null,
            supportingText = state.inputError?.let { message -> { Text(message) } },
        )
        OutlinedTextField(value = state.deliveryDetails, onValueChange = onDetailsChanged,
            label = { Text("Apartment / delivery details (optional)") },
            modifier = Modifier.fillMaxWidth(), maxLines = 4)
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Paste address") }
        PrimaryButton("Review address", onReview)
        Text("You can also share an address here from another app.", color = Muted, fontSize = 13.sp)
    }
    LocationStatusCard(state.live, onLocation, onLocationSettings, onAppSettings)
    HorizontalDivider(color = Muted.copy(alpha = 0.2f))
    Text("Take a practice walk", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    Text("Try the direction arrow, turn-around alert, and street-side guidance on a sample street.", color = Muted)
    OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("Explore demo") }
    Text("Live walking needs Google services and the private API setup. The demo remains separate.", color = Amber, fontSize = 13.sp)
}

@Composable
private fun PreviewScreen(onStart: () -> Unit) {
    Eyebrow("ARRIVAL PREVIEW · DEMO")
    Text("Know what you're\nwalking toward.", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    RouteSchematic(null)
    PanelCard {
        Tag("SAMPLE DESTINATION")
        Text("24 Oak Street", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Sample building · north side of the street", color = Muted)
        HorizontalDivider(color = Muted.copy(alpha = 0.2f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("100 m", "practice route")
            Metric("On foot", "walking mode")
        }
    }
    ImageryNotice()
    PrimaryButton("Start demo walk", onStart)
    Text("Practice controls move a simulated walker. No location or camera access is needed.", color = Muted, fontSize = 13.sp)
}

@Composable
private fun WalkScreen(
    snapshot: DemoWalk.Snapshot,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onCross: () -> Unit,
    onRestart: () -> Unit,
    onCue: (WalkCue) -> Unit,
) {
    val status = snapshot.route.status
    val acrossStreet = snapshot.side is SideGuidance.CrossStreet
    val cue = when {
        status == RouteStatus.ARRIVED -> WalkCue.ARRIVED
        status == RouteStatus.WRONG_WAY -> WalkCue.TURN_AROUND
        snapshot.side is SideGuidance.CrossStreet -> WalkCue.CHECK_SIDE
        else -> WalkCue.NONE
    }
    var previousCue by remember { mutableStateOf(WalkCue.NONE) }
    LaunchedEffect(cue) {
        if (cue != previousCue) onCue(cue)
        previousCue = cue
    }
    val title = when {
        status == RouteStatus.ARRIVED -> "You've arrived"
        status == RouteStatus.WRONG_WAY -> "Turn around"
        status == RouteStatus.OFF_ROUTE -> "Away from the route"
        acrossStreet -> "Check street side"
        else -> "Keep walking"
    }
    val instruction = when {
        status == RouteStatus.ARRIVED -> "Check the building number and delivery details."
        status == RouteStatus.WRONG_WAY -> "You're heading away from the sample building."
        status == RouteStatus.OFF_ROUTE -> "Return toward the sample route."
        acrossStreet -> "The sample building is across the street. Use a safe, permitted crossing."
        else -> "Follow Oak Street to the sample building."
    }
    Eyebrow("PRACTICE WALK · SIMULATED POSITION")
    Surface(color = if (status == RouteStatus.WRONG_WAY || acrossStreet) Amber else Lime, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    status == RouteStatus.ARRIVED -> "✓"
                    status == RouteStatus.WRONG_WAY -> "↶"
                    acrossStreet -> "↔"
                    else -> "↑"
                },
                color = Ink, fontSize = 70.sp, lineHeight = 76.sp,
                modifier = Modifier.semantics { contentDescription = "" },
            )
            Text(title, color = Ink, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Text(instruction, color = Ink, fontSize = 16.sp)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric(if (status == RouteStatus.ARRIVED) "Arrived" else "${snapshot.route.remainingM.roundToInt()} m", "to sample destination")
        Metric("±4 m", "simulated accuracy")
    }
    RouteSchematic(snapshot)
    if (status != RouteStatus.ARRIVED) SideCard(snapshot.side)
    if (status == RouteStatus.ARRIVED) {
        PanelCard {
            Text("24 Oak Street", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("Practice complete. In a real delivery, confirm the address before leaving the order.", color = Muted)
        }
        PrimaryButton("Restart demo", onRestart)
    } else {
        PanelCard {
            Eyebrow("DEMO CONTROLS")
            Text("Move the sample walker", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            PrimaryButton("Walk toward building · 6 m", onForward)
            OutlinedButton(onClick = onBackward, modifier = Modifier.fillMaxWidth()) { Text("Walk away · 6 m") }
            OutlinedButton(onClick = onCross, modifier = Modifier.fillMaxWidth()) { Text("Simulate street crossing") }
        }
    }
    Text("Sample street only · do not use this demo for real navigation", color = Amber, fontSize = 13.sp)
}

@Composable
private fun SideCard(guidance: SideGuidance) {
    if (guidance == SideGuidance.NotApplicable) return
    val relative = when (guidance) {
        is SideGuidance.Hint -> guidance.destinationOn
        is SideGuidance.OnCorrectSide -> guidance.destinationOn
        is SideGuidance.CrossStreet -> guidance.destinationOn
        SideGuidance.NotApplicable -> return
    }
    val side = if (relative == RelativeSide.LEFT) "left" else "right"
    PanelCard {
        Text(
            if (guidance is SideGuidance.CrossStreet) "Building is across the street" else "Building on your $side",
            color = if (guidance is SideGuidance.CrossStreet) Amber else Lime,
            fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
        )
        Text(
            when (guidance) {
                is SideGuidance.CrossStreet -> "Use a safe, permitted crossing. Check traffic and signals."
                is SideGuidance.Hint -> "Your current street side is uncertain."
                else -> "You're on the building's side of the street."
            }, color = Muted,
        )
    }
}

@Composable
private fun RouteSchematic(snapshot: DemoWalk.Snapshot?) {
    Column(Modifier.background(Panel, RoundedCornerShape(24.dp)).padding(18.dp)) {
        Eyebrow("SAMPLE STREET · SCHEMATIC")
        Canvas(Modifier.fillMaxWidth().height(190.dp).semantics {
            contentDescription = "Sample eastbound walking route. Destination on the north side of Oak Street."
        }) {
            val w = size.width
            val h = size.height
            drawRoundRect(Color(0xFF30423D), Offset(0f, h * .48f), Size(w, h * .36f), CornerRadius(10f))
            for (i in 0..8) drawLine(Muted.copy(alpha = .35f), Offset(w * i / 8, h * .66f), Offset(w * i / 8 + 12f, h * .66f), 3f)
            drawLine(Lime.copy(alpha = .25f), Offset(w * .10f, h * .36f), Offset(w * .86f, h * .36f), 20f, StrokeCap.Round)
            drawLine(Lime, Offset(w * .10f, h * .36f), Offset(w * .86f, h * .36f), 5f, StrokeCap.Round)
            drawRoundRect(Color(0xFF748D77), Offset(w * .77f, h * .02f), Size(w * .18f, h * .23f), CornerRadius(9f))
            drawRect(Ink, Offset(w * .84f, h * .12f), Size(w * .045f, h * .13f))
            drawCircle(Lime, radius = 12f, center = Offset(w * .86f, h * .36f))
            drawCircle(Ink, radius = 5f, center = Offset(w * .86f, h * .36f))
            val x = w * (.10f + ((snapshot?.eastM ?: 20.0) / 120.0).toFloat() * .76f)
            val y = h * if ((snapshot?.northM ?: 6.0) > 0) .36f else .92f
            drawCircle(Lime.copy(alpha = .16f), 32f, Offset(x, y))
            drawCircle(Ink, 19f, Offset(x, y))
            rotate(if ((snapshot?.route?.travelBearingDeg ?: 90.0) > 180.0) 180f else 0f, Offset(x, y)) {
                drawPath(Path().apply {
                    moveTo(x + 15f, y); lineTo(x - 10f, y - 10f); lineTo(x - 5f, y); lineTo(x - 10f, y + 10f); close()
                }, Lime)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Oak Street", color = Muted, fontSize = 12.sp)
            Text("24 · sample building", color = Lime, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ImageryNotice() {
    PanelCard {
        Text("Building imagery", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Street View is unavailable in this demo. Use the sample route and large direction arrow to practice.", color = Muted)
    }
}

@Composable
internal fun PanelCard(content: @Composable () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink), enabled = enabled) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tag(label: String) {
    Text(label, color = Lime, fontSize = 10.sp, letterSpacing = 1.sp,
        modifier = Modifier.background(Lime.copy(alpha = .10f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
internal fun Eyebrow(label: String) { Text(label, color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium) }

@Composable
internal fun Metric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}
