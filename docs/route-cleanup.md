# Route cleanup, 2026-09-14

User requested cleanup of unused Maps patch code after review. Changes are local on `main` in `GoogleMapsMicroGPatch.kt` only, apart from this record and the workspace handoff.

- Removed the 49-entry `exactGmsRoutes` allowlist and its `toRevancedRoute` wrapper. Direct lookup in `exactGmsRouteReplacements` retains the nine actual replacements; the other 40 entries previously returned the input unchanged.
- Preserved priority of `exactStringReplacements`, then exact route replacements, then content URI rewriting.
- Removed the unused default `name = "b"` argument from `googlePlayUtilityFingerprint`; both existing call sites explicitly pass `"o"`.
- Net source reduction: 56 lines. Location service action resolution, GmsCore/auth metadata, availability hooks, BYD audio, and compatibility targets remain unchanged.

## Verification

Compared the source before and after this cleanup in memory. The expected three edits account for the entire difference. All nine replacement keys belong to the old route allowlist, and none of its 49 routes begins with any content-URI prefix handled by the fallback. Therefore replacing the allowlist dispatch with direct map lookup preserves the result for every input string. The maps and URI rewrite implementation were unchanged. Both fingerprint call sites still explicitly pass `"o"`.

`:patches:compileKotlin` passed in 30 seconds and `git diff --check` passed. No test files, APK patching, bundle generation, ADB, commit, push or release were performed. This is source cleanup with compile/static verification; no new device-tested artifact was produced.

Preserve the existing location-fix bundle and APK bytes. They predate this cleanup and must not be represented as built from the cleaned source. A later bundle build must follow the candidate/version workflow instead of silently replacing these verified artifacts.

- Existing `patches-1.0.13.mpp` SHA-256: `7a5b232509eabee0595b08a8f8a084cc89b35f089bee3567a6b4454ebc2ffb07`.
- Existing Maps 26.36 location-fix APK SHA-256: `8c4d7d26699fb8817a5345b1d7f9c458b5be6800fbb64e920471c373c837527c`.
