package app.morphe.patches.maps

import app.morphe.patcher.patch.bytecodePatch

/**
 * Optional patches share the core's compatibility target and extension resources.
 *
 * The core must run first to inject the extension DEX and initialize MicroG support.
 * Keep these named patches independent so phones can omit BYD-specific changes.
 */
@Suppress("unused")
val deviceCompatibilityPatch = bytecodePatch(
    name = "Device certification and service resolution fixes",
    description = "Skips the unsupported-device certification screen and handles unavailable Play Services resolution dialogs.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(googleMapsMicroGPatch)

    execute {
        patchDeviceComplianceCheck()
        patchGmsResolutionStart()
    }
}

@Suppress("unused")
val navigationSpeechPatch = bytecodePatch(
    name = "Navigation speech and TTS startup",
    description = "Improves synthesized navigation guidance and initializes the TTS engine early. Requires the MicroG Maps core.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(googleMapsMicroGPatch)

    execute {
        patchNavigationTts()
        injectNavigationTtsWarmup()
    }
}

@Suppress("unused")
val bydNavigationAudioPatch = bytecodePatch(
    name = "BYD navigation audio routing",
    description = "Routes navigation prompts to the BYD-specific audio stream when supported, retaining the original fallback. Not needed on phones.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(googleMapsMicroGPatch)

    execute {
        patchBydNavigationAudio()
    }
}
