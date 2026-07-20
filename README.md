<a id="google-maps-microg"></a>

# Google Maps for ReVanced GmsCore

An unofficial `.mpp` patch source for using supported Google Maps APKs with [ReVanced GmsCore](https://github.com/ReVanced/GmsCore) (`app.revanced.android.gms`) through [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

This repository does not distribute Google Maps APKs, whether original or patched.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.4](https://github.com/fangkampanat/gmaps-patches/releases/tag/v1.0.4)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patch total
<details open>
<summary>📦 Google Maps&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 26.26.04.935742811 | 26.27.05.941319029 | 26.28.03.942936911 |
| :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Google Maps MicroG](#google-maps-microg) | Routes supported Google Maps builds through ReVanced GmsCore using the patched Maps package and known Google Maps certificate spoof metadata. |  |

</details>

<!-- PATCHES_END -->

## Requirements

- A computer with Java 21 or newer (Google Maps is too large for practical patching on a phone or tablet)
- The latest [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop/releases/latest)
- A clean Google Maps APK matching a version listed in the Patches list

## GmsCore setup and tested configurations

The patched APK requires ReVanced GmsCore for Google sign-in and related Google services. Use the configuration that matches the target device:

| Target device | GmsCore configuration | Validation |
|---|---|---|
| Official Google Play services installed | **User** variant of [ReVanced GmsCore v0.3.13.3.250932](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.3.250932) (prerelease) | Tested on an Android tablet. This version includes [improvements for side-by-side installation](https://github.com/ReVanced/GmsCore/commit/776a8fb) with official Google services. |
| No official GMS | [ReVanced GmsCore v0.3.13.2.250932](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932) | Tested on a BYD vehicle head unit. |

> **Not supported:** [Morphe MicroG-RE](https://github.com/MorpheApp/MicroG-RE) `v6.1.4` was tested but did not work with this patched APK. Google account token refresh failed with `INVALID_APPID`, leaving Maps offline.

Other GmsCore versions may work with the patched APK but have not been verified by this project.

## Add the patch source

The source must be added manually in Morphe Desktop:

1. Open **Settings** and enable **Expert mode**.
2. Click the **N Sources** button at the top center of the window.
3. Click **Add Source** and select **Remote**.
4. Enter:

   | Field | Value |
   |---|---|
   | Name | `Google Maps for ReVanced GmsCore` |
   | Repository URL | `https://github.com/fangkampanat/gmaps-patches` |

5. Click **Add**, then confirm the source is enabled and set to **Follow Stable**.

Only tested Stable bundles are published by this repository.

## Patch Google Maps

1. Select a clean, supported Google Maps APK in Morphe Desktop.
2. Review the compatible patches and make sure **Google Maps MicroG** (the current Stable patch name) is enabled.
3. Do not use **Continue Anyway** for an unsupported APK.
4. Start patching, then install the resulting APK on the target device.
5. Keep `morphe-data/morphe.keystore` backed up. The same key is required for update-over-install.

## Credits

- [Morphe](https://github.com/MorpheApp) for Morphe Desktop, patching tools, and the upstream patch code this project builds upon.
- [ReVanced](https://github.com/ReVanced) for the original GmsCore support implementation and ReVanced GmsCore.

## Notes

- A `.mpp` file is a patch bundle, not an installable APK.
- The patched app uses package name `app.morphe.android.apps.maps`.
- The project is unofficial and is not supported or endorsed by Google, BYD, ReVanced, or Morphe.
- Source code is licensed under [GPL-3.0](LICENSE). Preserve [NOTICE](NOTICE) in source and derivative distributions.
