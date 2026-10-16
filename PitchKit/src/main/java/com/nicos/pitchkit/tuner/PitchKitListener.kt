package com.nicos.pitchkit.tuner

import android.Manifest
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nicos.pitchkit.BuildConfig
import com.nicos.pitchkit.tuner.extensions.findActivity
import com.nicos.pitchkit.tuner.extensions.toPublic
import com.nicos.pitchkit.tuner.models.InstrumentProfile
import kotlinx.coroutines.flow.catch

private const val TAG = "PitchKit"

/**
 * Listens to the microphone and streams detected notes and chords as typed [TuningResult] values through [onResult]
 * Handles the microphone permission internally (including the rationale popup and
 * the permanently-denied → Settings path), so the caller only handles results.
 *
 * @param profile the instrument to detect for (frequency range, sharps/flats). Defaults to [InstrumentProfile.Guitar].
 * @param titleText shown in the popup title.
 * @param permanentlyDeniedText shown in the popup when the user has permanently
 * denied the permission (the button then opens Settings).
 * @param rationaleText shown in the popup when the permission can still be requested.
 * @param openSettingsText label for the confirm button when the permission is
 * permanently denied, and it opens Settings.
 * @param allowText label for the confirm button when the permission can still be requested.
 * @param dismissText label for the dismiss button.
 * @param onError called once if the microphone can't be used (busy, revoked,
 * device error). Detection stops after this; it restarts when [profile]
 * changes or the composable re-enters composition.
 * @param onResult called with each detection result ([TuningResult.Note],
 * [TuningResult.Chord], or [TuningResult.Silence]).
 */
@Composable
fun PitchKitListener(
    profile: InstrumentProfile = InstrumentProfile.Guitar,
    titleText: String = "Microphone needed",
    permanentlyDeniedText: String = "Microphone access is blocked. Please enable it in Settings to tune your guitar.",
    rationaleText: String = "This app needs microphone access to detect notes and chords from your guitar.",
    openSettingsText: String = "Open Settings",
    allowText: String = "Allow",
    dismissText: String = "Not now",
    onError: (Throwable) -> Unit = {},
    onResult: (TuningResult) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

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

    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)

    // ---- Engine + collection, active only while permission is held ----
    if (granted) {
        LaunchedEffect(profile) {
            TunerEngine(profile = profile).start()
                .catch { e ->
                    // Catch here so a mic failure never crashes the host app
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Audio capture failed", e)
                    }
                    // Clear the display first: the last detected note is no longer live
                    currentOnResult(TuningResult.Silence)
                    currentOnError(e)
                }
                .collect { result ->
                    currentOnResult(result.toPublic())
                    if (BuildConfig.DEBUG) {
                        // For internal/debug purpose
                        val finalResult = when (result) {
                            is TunerEngine.Result.Note ->
                                "${result.name} ${result.freq} (${"%.0f".format(result.cents)}¢)"

                            is TunerEngine.Result.Chord -> result.name
                            TunerEngine.Result.Silence -> "—"
                        }
                        Log.d(TAG, finalResult)
                    }
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

/**
 * Default popup texts, shared by [PitchKitListener] and the deprecated
 * [GuitarTunerListener], so the two can never show different texts.
 * Neutral wording: PitchKit is used for chord detection, practice apps, tuners, etc.
 */
internal object PitchKitDefaults {
    const val TITLE = "Microphone needed"
    const val PERMANENTLY_DENIED = "Microphone access is blocked. Please enable it in Settings."
    const val RATIONALE = "This app needs microphone access to detect notes and chords."
    const val OPEN_SETTINGS = "Open Settings"
    const val ALLOW = "Allow"
    const val DISMISS = "Not now"
}