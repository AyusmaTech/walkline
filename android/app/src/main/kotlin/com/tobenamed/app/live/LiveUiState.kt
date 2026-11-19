package com.tobenamed.app.live

import com.tobenamed.nav.live.WalkingProgress
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.LocationSample

data class LiveUiState(
    val candidates: List<AddressCandidate> = emptyList(),
    val destination: AddressCandidate? = null,
    val streetApproach: StreetApproach? = null,
    val approachMessage: String? = null,
    val source: String = "openstreetmap",
    val busy: Boolean = false,
    val error: String? = null,
    val locationRequested: Boolean = false,
    val fix: LocationSample? = null,
    val facing: DeviceFacing? = null,
    val locationMessage: String? = null,
    val testLocation: Boolean = false,
    val route: WalkingRoute? = null,
    val progress: WalkingProgress? = null,
)
