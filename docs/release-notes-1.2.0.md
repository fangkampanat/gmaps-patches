Allow patching any Google Maps version with generic hook lookup, based on PR #7 by Harvey843. Resolve lifecycle hooks through stable entry classes and identify the Play services error-code field from its statusCode value flow. Reject ambiguous audio and notification hooks, and log when the optional legacy service check is absent.

Use Google Maps Morphe in patch metadata and output filenames. The installed app name and package remain unchanged. Requires MicroG-RE-BYD.

Version eligibility is unrestricted; compatibility with every Maps release is not guaranteed. New Maps versions can reuse this bundle when patching, static verification and runtime testing pass.

Validated the final bundle on Maps 26.36.05.973607363: ARM64 APK signature and 16 KiB alignment passed, along with phone sign-in, online maps, real-GPS navigation and account/state retention after reinstalling the same APK. Generic code regression checks covered 26.29, 26.35 and 26.37 beta with the code-identical local 1.1.2 bundle; 26.35 was statically verified only.

Bundle SHA-256: `587c7efef607d6c82c154d050b9410ae38bb9e58fda4c069820197ea50bcaa73`.
