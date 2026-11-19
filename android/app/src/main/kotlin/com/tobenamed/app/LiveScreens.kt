package com.tobenamed.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.app.live.LiveUiState
import com.tobenamed.nav.model.GeocodePrecision
import kotlin.math.roundToInt
import java.util.Locale

private val WarningColor = Color(0xFFFFCD87)

@Composable
fun AddressReviewScreen(state: WalkUiState, onAddress: (String) -> Unit, onDetails: (String) -> Unit,
    onResolve: () -> Unit, onEdit: () -> Unit, onDemo: () -> Unit) {
    Eyebrow("ADDRESS REVIEW")
    Text("Find the right building.", fontSize = 32.sp, fontWeight = FontWeight.Bold)
    PanelCard {
        OutlinedTextField(state.address, onAddress, modifier = Modifier.fillMaxWidth(),
            label = { Text("Street address, city and postcode") }, minLines = 2, maxLines = 5, enabled = !state.live.busy)
        OutlinedTextField(state.deliveryDetails, onDetails, modifier = Modifier.fillMaxWidth(),
            label = { Text("Apartment / delivery details") }, maxLines = 4, enabled = !state.live.busy)
        Text("Keep apartment numbers and delivery instructions in the details field. Only the street address is sent for lookup.",
            color = MaterialTheme.colorScheme.secondary)
    }
    ErrorText(state.live.error)
    PrimaryButton(if (state.live.busy) "Finding address…" else "Find building", onResolve, !state.live.busy)
    if (state.live.busy) CircularProgressIndicator()
    Text("You'll confirm the address match before starting a walk.", color = MaterialTheme.colorScheme.secondary)
    OutlinedButton(onEdit, Modifier.fillMaxWidth()) { Text("Edit address") }
    TextButton(onDemo) { Text("Try a sample destination") }
}

@Composable
fun AddressMatchesScreen(state: WalkUiState, onChoose: (AddressCandidate) -> Unit) {
    SourceLabel(state.live.source)
    Text("Confirm the address.", fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text("Check the street, city and postcode. An approximate match may cover several buildings.", color = MaterialTheme.colorScheme.secondary)
    state.live.candidates.forEach { candidate ->
        PanelCard {
            Text(candidate.address, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(precisionLabel(candidate.precision), color = MaterialTheme.colorScheme.secondary)
            if (candidate.partialMatch) Text("Partial address match — check every detail.", color = WarningColor)
            PrimaryButton("Open navigation", { onChoose(candidate) })
        }
    }
    if (state.deliveryDetails.isNotBlank()) PanelCard {
        Eyebrow("YOUR DELIVERY DETAILS")
        Text(state.deliveryDetails)
    }
    Text(when (state.live.source) {
        "fixture" -> "Synthetic test results; not a resolved real address."
        "openstreetmap" -> "Address data: © OpenStreetMap contributors · Photon"
        else -> "Address data: Google"
    }, color = MaterialTheme.colorScheme.secondary)
}

@Composable
fun LocationStatusCard(state: LiveUiState, onLocation: () -> Unit, onSettings: () -> Unit, onAppSettings: () -> Unit) {
    PanelCard {
        Eyebrow(if (state.testLocation) "TEST / EMULATOR LOCATION" else "PHONE LOCATION")
        val fix = state.fix
        if (fix == null) Text("Get a fresh GPS fix", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        else {
            Text("Last fix: ±${fix.accuracyM.roundToInt()} m", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "%.5f, %.5f", fix.position.lat, fix.position.lng), color = MaterialTheme.colorScheme.secondary)
        }
        state.locationMessage?.let { Text(it, color = WarningColor) }
        OutlinedButton(onLocation, Modifier.fillMaxWidth()) { Text("Get my location") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onSettings) { Text("Location settings") }
            TextButton(onAppSettings) { Text("App permissions") }
        }
        Text("Foreground only. Guidance pauses when you leave the app or lock the phone.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SourceLabel(source: String) { Eyebrow(when (source) {
    "fixture" -> "TEST SERVICE · SYNTHETIC ROUTE DATA"
    "openstreetmap" -> "LIVE WALKING · OPENSTREETMAP"
    else -> "LIVE WALKING · GOOGLE SERVICES"
}) }

@Composable
private fun ErrorText(error: String?) { if (error != null) Text(error, color = WarningColor) }

private fun precisionLabel(precision: GeocodePrecision) = when (precision) {
    GeocodePrecision.ENTRANCE -> "Entrance-level location"
    GeocodePrecision.ROOFTOP -> "Building-level location · street preview next"
    GeocodePrecision.INTERPOLATED -> "Estimated address along the street"
    GeocodePrecision.APPROXIMATE -> "Approximate area · verify the building yourself"
}
