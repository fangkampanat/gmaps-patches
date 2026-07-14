# Google Maps MicroG for ReVanced GmsCore

An unofficial Google Maps MicroG patch bundle for [ReVanced GmsCore](https://github.com/ReVanced/GmsCore) (`app.revanced.android.gms`). It patches selected original Google Maps APK versions so they can use ReVanced GmsCore for Google account and service compatibility. ReVanced GmsCore is also commonly searched for as **ReVanced MicroG**.

[**Add Google Maps MicroG to Morphe Manager**](https://morphe.software/add-source?github=fangkampanat%2Fgmaps-patches%2Fblob%2Frefs%2Fheads%2Fmain%2Fpatches-bundle.json&name=Google%20Maps%20MicroG)

The bundle is designed for Morphe-compatible patching workflows while keeping an independent project identity. It is not an official Google Maps, ReVanced, or Morphe release.

This repository contains patch source code. GitHub Releases may contain compiled `.mpp` patch bundles only. The project does not distribute original or patched Google Maps APKs, signing keys, or user data.

> [!IMPORTANT]
> A `.mpp` file is a patch bundle, not an installable APK. Install [Morphe Manager](https://morphe.software/), add this GitHub repository URL as a patch source, and provide your own compatible clean Google Maps APK.

## Quick start

1. Install [ReVanced GmsCore](https://github.com/ReVanced/GmsCore).
2. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) on the Android device that will perform the patching.
3. [Add Google Maps MicroG to Morphe Manager](https://morphe.software/add-source?github=fangkampanat%2Fgmaps-patches%2Fblob%2Frefs%2Fheads%2Fmain%2Fpatches-bundle.json&name=Google%20Maps%20MicroG), or manually add `https://github.com/fangkampanat/gmaps-patches/blob/refs/heads/main/patches-bundle.json` as a custom patch source.
4. Select a clean Google Maps APK matching a version in the compatibility table below.
5. Apply `Google Maps MicroG`, then install the resulting APK.

## What the patch does

The `Google Maps MicroG` patch:

- changes the Maps package from `com.google.android.apps.maps` to `app.morphe.android.apps.maps`;
- routes supported Google Play services calls to ReVanced GmsCore at `app.revanced.android.gms`;
- adds the original Maps package and certificate metadata required by GmsCore;
- declares accepted Google Maps signer certificates as compatibility requirements;
- disables picture-in-picture support declared by Maps activities;
- rewrites relevant manifest permissions, providers, actions, and content-provider routes;
- bypasses Google Play services availability checks used by supported Maps builds; and
- preserves a separate application identity so the patched build can coexist with another Maps installation outside the same Android package space.

## Compatibility

| Google Maps version | Status |
|---|---|
| `26.27.05.941319029` | Validated on a BYD Dolphin infotainment system |
| `26.26.04.935742811` | Legacy compatibility target |

Version `26.27.05.941319029` was confirmed to launch, recognize the GmsCore account, load online maps, obtain GPS location, and navigate normally during real use on the vehicle. Tablet validation covered launch, account access, online maps, and update-over-install; tablet GPS was intentionally not used as a validation source.

Compatibility is fingerprint-based. A newer Google Maps release must be inspected before support is added; a successful build alone does not prove runtime compatibility.

## Requirements for users

- A clean, supported Google Maps APK
- ReVanced GmsCore installed as `app.revanced.android.gms`
- Morphe Manager or another compatible Morphe patching tool
- A consistent signing key when updating an existing patched installation

## Build requirements

- JDK 21
- Android SDK with API 36 available

GitHub Packages may require `GITHUB_ACTOR` and `GITHUB_TOKEN` with package-read access when Gradle dependencies are not already cached.

## Build the patch bundle

From the repository root on Windows PowerShell:

```powershell
.\gradlew.bat --console=plain :patches:buildAndroid
```

The generated bundle is written to:

```text
patches/build/libs/patches-*.mpp
```

Build directories and generated `.mpp` files are ignored by Git. Publish an `.mpp` as a GitHub Release asset instead of committing it to repository history.

## Releases

GitHub Releases contain compiled `patches-*.mpp` bundles for supported Google Maps versions. Morphe Manager can retrieve a compatible bundle after this repository is added as a patch source. Release assets do not include original Google Maps APKs or ready-patched APKs; users must supply a compatible clean APK and patch it locally.

## Use

1. Add this GitHub repository URL as a patch source in Morphe Manager, or build/download the `.mpp` bundle for another compatible workflow.
2. Let the patching tool load the matching patch bundle.
3. Select a clean APK whose full version matches a declared compatibility target.
4. Apply `Google Maps MicroG`.
5. Rebuild and sign with the same key used for previous versions of the patched app.
6. Validate on a non-critical device before installing it on a vehicle.

Local artifact naming convention:

```text
Original APK: og-GMaps-<full-version>.apk
Patched APK:  GMapsMicroG-<short-version>_<DMonYYYY>.apk
```

`DMonYYYY` is derived from the original APK file's `LastWriteTime` using invariant English month names. It is not the patch execution date. Never overwrite an existing output with the same derived name.

Do not commit or publish original/patched Google Maps APKs, signing keystores, passwords, result files, or device logs containing personal information.

## Repository layout

```text
extensions/maps/   Runtime GmsCore support extension
extensions/shared/ Shared extension dependencies
patches/           Google Maps patch and Android stubs
gradle/            Gradle wrapper and dependency definitions
```

## Scope and limitations

- Only the versions declared in the patch source are supported.
- BYD vehicle testing is authoritative for GPS and navigation behavior in this project.
- Google server-side behavior, account policy, Play Integrity, and future Maps changes are outside the patch's control.
- The patched application is unofficial and is not supported by Google, BYD, ReVanced, or Morphe.

## Credits

- [Morphe Patches](https://github.com/MorpheApp/morphe-patches) for the patch framework and shared source this project derives from.
- [ReVanced Patches](https://github.com/ReVanced/revanced-patches) for the original GmsCore support implementation referenced by the runtime extension.
- [ReVanced GmsCore](https://github.com/ReVanced/GmsCore) for the active Google services compatibility provider.

Copyright and attribution notices in individual source files must be preserved.

## License

This project is distributed under the [GNU General Public License v3.0](LICENSE), including the additional GPLv3 Section 7 conditions recorded in [NOTICE](NOTICE).

`NOTICE` must remain in source and derivative distributions. **Maps Patches** is an independent project name and is not affiliated with or endorsed by MorpheApp.

Google Maps and related names are trademarks of Google LLC. BYD is a trademark of its respective owner.
