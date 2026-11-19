package com.tobenamed.app

import android.content.ClipboardManager
import android.content.Intent
import android.Manifest
import android.provider.Settings
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.net.toUri
import com.tobenamed.app.live.ForegroundLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: WalkViewModel by viewModels()
    private lateinit var location: ForegroundLocation
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ForegroundLocation.hasLocationPermission(this)) {
            model.beginLocationTracking()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) location.start()
        } else model.locationProblem("Location permission was denied. You can enable it in app settings.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) receiveShare(intent)
        location = ForegroundLocation(this, { window.decorView.display?.rotation ?: 0 }, model::onLocation, model::locationProblem, model::onFacing)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    launch {
                        while (true) { delay(2_000); model.refreshLocationAge() }
                    }
                    model.state.map { it.live.locationRequested }.distinctUntilChanged().collect { requested ->
                        if (requested && ForegroundLocation.hasLocationPermission(this@MainActivity)) location.start() else location.stop()
                    }
                } finally { location.stop(); model.pauseLocation() }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            WalkApp(
                state = state,
                onAddressChanged = model::editAddress,
                onPaste = ::pasteAddress,
                onReview = model::reviewAddress,
                onDemo = model::showDemo,
                onStart = model::startDemo,
                onForward = { model.advance(true) },
                onBackward = { model.advance(false) },
                onCross = model::crossStreet,
                onBack = model::back,
                onCue = ::vibrate,
                onDetailsChanged = model::editDeliveryDetails,
                onResolve = model::resolveAddress,
                onChoose = model::chooseDestination,
                onLocation = ::requestLocation,
                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
                onStartLive = model::startLiveWalk,
                onSelectStreet = model::selectStreet,
            )
        }
    }

    private fun requestLocation() {
        if (ForegroundLocation.hasLocationPermission(this)) {
            model.beginLocationTracking()
            location.start()
        } else permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(model::receiveAddress)
        }
    }

    private fun pasteAddress() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        // Read only in response to Paste, with no background clipboard access or URI resolution.
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString()?.let(model::editAddress)
        }
    }

    private fun vibrate(cue: WalkCue) {
        val pattern = when (cue) {
            WalkCue.TURN -> longArrayOf(0, 65)
            WalkCue.TURN_AROUND -> longArrayOf(0, 160, 100, 160, 100, 160)
            WalkCue.CHECK_SIDE -> longArrayOf(0, 90, 90, 90)
            WalkCue.ARRIVED -> longArrayOf(0, 80, 80, 220)
            WalkCue.NONE -> return
        }
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Vibrator::class.java)
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
