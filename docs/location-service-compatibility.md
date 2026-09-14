# Location service action compatibility

Starting with the 1.1.0 line, MicroG-RE-BYD is the only supported provider. The resolver below is retained as protocol compatibility code, not as a commitment to support ReVanced or upstream RE. MicroG-RE-BYD byd.5 exposes the renamed action. Maps 26.36 with the local 1.0.13 cleanup bundle passed user testing on the BYD Dolphin Head Unit and the official-GMS phone. The final 1.1.0 APK subsequently passed the phone gate on 2026-09-14. See the [1.1.0 release notes](release-notes-1.1.0.md) for test scope and the account-capability warning. Earlier diagnostic findings below are historical.

The candidate patch selects the location action exposed by the installed `app.revanced.android.gms` provider. It queries the original Google action first and uses the renamed action only when the original cannot resolve to an enabled, exported provider service. Both queries are restricted to that package. If neither resolves or the extension context is unavailable, the original action is retained. Resolution is not cached across calls.

- ReVanced GmsCore uses `com.google.android.location.internal.GoogleLocationManagerService.START`.
- MicroG-RE 7.1.1 exposes `app.revanced.android.location.internal.GoogleLocationManagerService.START`. Its [location manifest](https://github.com/MorpheApp/MicroG-RE/blob/7.1.1/play-services-location/core/src/main/AndroidManifest.xml) declares an exported service without a binding permission.
- The existing package query makes the selected provider visible to PackageManager. Auth actions, Binder descriptors, other service actions, location permissions and the provider package are unchanged.

The patch validates exactly two literal action getters with a const-string/return-object body in each inspected APK. Maps 26.36 uses `Lbfem;->d` and `Lbfgd;->d`; Maps 26.35 uses `Lbfda;->d` and `Lbfbi;->d`. Each getter calls the extension resolver. An unexpected count or method shape fails patching.

On the test device, the prior candidate could not bind this service on MicroG-RE 7.1.1: the original action resolved to zero services and the renamed action to one. This change addresses that demonstrated mismatch. It does not establish complete MicroG-RE compatibility, solve every other missing service, or prove that real-GPS navigation works. The earlier ReVanced callback-cancellation behavior is a separate unresolved issue.

Static checks cover both inspected Maps targets because this changes shared runtime behavior. Device verification must use the new final APK, check that the location service now binds, and then verify continuous real-GPS fixes and navigation. Existing Stable provider records remain historical until the new candidate passes its gate.
