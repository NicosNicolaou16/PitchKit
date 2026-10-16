package com.nicos.pitchkit.tuner

import androidx.compose.runtime.Composable
import com.nicos.pitchkit.tuner.models.InstrumentProfile

/**
 * Deprecated alias of [PitchKitListener]; behaves identically.
 * Kept so apps written for 1.0.1 still compile. Will be removed in a future version.
 *
 * To migrate, rename the call to `PitchKitListener` — the parameters are the same.
 */
@Suppress("DeprecatedCallableAddReplaceWith")
@Deprecated(
    // No replaceWith on purpose: ReplaceWith rewrites the whole call and fills in
    // every default value (including internal PitchKitDefaults, which wouldn't compile
    // in the user's app). Since the signature is identical, renaming is all that's needed.
    message = "Renamed to PitchKitListener (same parameters). " +
            "Just rename the call. GuitarTunerListener will be removed in a future version.",
    level = DeprecationLevel.WARNING,
)
@Composable
fun GuitarTunerListener(
    profile: InstrumentProfile = InstrumentProfile.Guitar,
    titleText: String = PitchKitDefaults.TITLE,
    permanentlyDeniedText: String = PitchKitDefaults.PERMANENTLY_DENIED,
    rationaleText: String = PitchKitDefaults.RATIONALE,
    openSettingsText: String = PitchKitDefaults.OPEN_SETTINGS,
    allowText: String = PitchKitDefaults.ALLOW,
    dismissText: String = PitchKitDefaults.DISMISS,
    onError: (Throwable) -> Unit = {},
    onResult: (TuningResult) -> Unit,
) {
    PitchKitListener(
        profile = profile,
        titleText = titleText,
        permanentlyDeniedText = permanentlyDeniedText,
        rationaleText = rationaleText,
        openSettingsText = openSettingsText,
        allowText = allowText,
        dismissText = dismissText,
        onError = onError,
        onResult = onResult,
    )
}