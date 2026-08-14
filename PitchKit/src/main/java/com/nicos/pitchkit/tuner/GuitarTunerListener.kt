package com.nicos.pitchkit.tuner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nicos.pitchkit.BuildConfig
import com.nicos.pitchkit.tuner.extensions.toPublic

/**
 * Runs the guitar tuner and streams formatted results back through [onResult].
 * Handles the microphone permission internally (including the popup and the
 * permanently-denied → Settings path), so the caller just receives result strings.
 * @param titleText shown in the popup title.
 * @param permanentlyDeniedText shown in the popup when the user permanently
 * denied the permission.
 * @param rationaleText shown in the popup when the user hasn't denied the
 * permission yet.
 * @param openSettingsText shown in the popup when the user permanently denied
 * the permission and wants to go to Settings.
 * @param allowText shown in the popup when the user hasn't denied the
 * permission yet.
 * @param dismissText shown in the popup when the user dismisses it.
 * @param onResult called with each result.
 */
@Composable
fun GuitarTunerListener(
    profile: InstrumentProfile = InstrumentProfile.Guitar,
    titleText: String = "Microphone needed",
    permanentlyDeniedText: String = "Microphone access is blocked. Please enable it in Settings to tune your guitar.",
    rationaleText: String = "This app needs microphone access to detect notes and chords from your guitar.",
    openSettingsText: String = "Open Settings",
    allowText: String = "Allow",
    dismissText: String = "Not now",
    onResult: (TuningResult) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showDialog by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
        if (!isGranted) {
            permanentlyDenied = activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.RECORD_AUDIO
                )
            } ?: false
            showDialog = true
        }
    }

    // Re-check on resume so returning from Settings (where the user may have
    // granted it) picks the permission up automatically.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) showDialog = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request on first appearance if not already granted.
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ---- Engine + collection, active only while permission is held ----
    if (granted) {
        val engine = remember(granted) { TunerEngine(profile = profile) }
        LaunchedEffect(granted) {
            engine.start().collect { result ->
                val tuningResult: TuningResult = result.toPublic()
                // For internal purpose
                val finalResult = when (result) {
                    is TunerEngine.Result.Note ->
                        "${result.name} ${result.freq} (${"%.0f".format(result.cents)}¢)"

                    is TunerEngine.Result.Chord -> result.name
                    TunerEngine.Result.Silence -> "—"
                }
                if (BuildConfig.DEBUG) Log.d("GuitarTuner", finalResult)
                onResult(tuningResult)
            }
        }
    }

    // ---- Popup when permission is missing ----
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(titleText) },
            text = {
                Text(
                    if (permanentlyDenied) permanentlyDeniedText
                    else rationaleText
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    if (permanentlyDenied) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    } else {
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Text(if (permanentlyDenied) openSettingsText else allowText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(dismissText) }
            }
        )
    }
}