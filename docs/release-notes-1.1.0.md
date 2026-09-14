# gmaps-patches 1.1.0 — MicroG-RE-BYD

## Move to MicroG-RE-BYD

MicroG-RE-BYD is now the only supported provider for gmaps-patches. Please migrate from ReVanced GmsCore or upstream MicroG-RE to the [latest MicroG-RE-BYD release](https://github.com/fangkampanat/MicroG-RE-BYD/releases/latest).

We have focused the provider improvements on BYD head units: corrected location identity handling, Maps location-setting compatibility, and auth package/signature handling. The Maps patch retains BYD navigation audio routing and selects the location service action exposed by the provider.

## Compatibility and testing

- Google Maps targets: `26.36.05.973607363` and `26.35.04.969485213`. Both passed patching and static verification with the final 1.1.0 bundle.
- Tested provider: MicroG-RE-BYD `7.1.1-byd.5`, ARM64 with a launcher icon.
- BYD Dolphin head unit: Maps navigation and use after vehicle restart passed user testing with the preceding 1.0.13 bundle and Maps 26.36.05.
- Android phone with official Google Play services: the final 1.1.0 APK passed user testing for account access, online maps, real-GPS navigation with numeric ETA/distance, and normal use. Installation over the previous version preserved app data; installed hash and launch checks passed.
- Existing Morphe-patched YouTube Music and YouTube apps are reported by the user to work normally with this provider. gmaps-patches continues to patch Google Maps only.

## Changes

- Rename the patch to **Google Maps for MicroG-RE-BYD** and update provider setup/migration instructions.
- Send the missing-provider download action to the MicroG-RE-BYD release page.
- Keep package `app.revanced.android.gms` for compatibility with existing Morphe apps.
- Remove redundant Maps string-routing code while preserving its output.
- Preserve existing auth routing, BYD navigation audio, permission rewrites, notification suppression and the original picture-in-picture declaration.

## Migration

Different provider signing keys may prevent update-over-install from ReVanced or upstream RE. If removal is required, the old provider's accounts/settings are cleared and need to be configured again. Do not remove official Google Play services. Use the signed ARM64 APK from MicroG-RE-BYD releases and follow its BYD background-operation setup guide.

You do not need to repatch compatible existing Morphe YouTube/YouTube Music APKs solely to switch providers. Reopen each app and check sign-in and playback after setup.

## Known limitations

One microG account-capability lookup logged `UNREGISTERED_ON_API_CONSOLE` during phone testing. This was the provider's account-state lookup, not an observed Maps sign-in or navigation failure. No `INVALID_APPID`, crash, ANR or SecurityException was found in the inspected app/provider logs. Timeline recording and backup have not been tested.

This release distributes the Maps `.mpp` bundle, not Google Maps APKs. Earlier public releases retain their recorded requirements and hashes.

Bundle SHA-256: `b4778f61916ad5a53837cba940a2bbec60dca22e90a9fa41aec10bfa43bb005e`.
