# Local Gradle authentication

Morphe settings plugin 1.3.3 requires GitHub Packages credentials during configuration, even when dependencies are already cached. Missing values previously caused `IllegalArgumentException` in `SettingsPlugin.configureDependencies`. The settings file now reports the missing configuration before applying the plugin.

## One-time setup

1. Create a GitHub personal access token **classic** with `read:packages` at <https://github.com/settings/tokens/new?scopes=read:packages&description=Morphe%20Gradle%20package%20read>. Choose an expiry suitable for your build schedule.
2. Set `gpr.user` to the GitHub username and `gpr.key` to the token in `%USERPROFILE%\.gradle\gradle.properties`, or the `GRADLE_USER_HOME` override when set. Preserve unrelated properties and keep credentials outside the repository, chat, and logs.
3. Restrict access to this plaintext credentials file. The existing file permits the current Windows user and SYSTEM.

Setup is already complete on this machine. The one-time setup script was removed after successful configuration and is not required for future builds.

Then verify configuration without producing or replacing a patch bundle:

```powershell
.\gradlew.bat --no-daemon --console=plain help
.\gradlew.bat --no-daemon --console=plain generatePatchesList --dry-run
```

Use the normal metadata, README, and Android build commands in `PATCHING_HANDOFF.md` for the next candidate. Do not rebuild an existing candidate that has already passed static verification.

## Subsequent builds

No credential command-line arguments are needed. Gradle reads the user-level file automatically. `GITHUB_ACTOR` and `GITHUB_TOKEN` remain supported for environments that explicitly supply them. A separate `GRADLE_USER_HOME` needs its own setup; `-P` arguments override the file and should not contain stale placeholders.

`--offline` only changes dependency resolution. It does not authenticate to GitHub Packages and is not the permanent fix. Never use `local-build` / `unused-offline` credentials for normal builds. Cache misses may require network access. Re-run setup when a token expires, is revoked, or loses package access.

GitHub CLI login alone does not populate Gradle properties. On 2026-09-13 the existing CLI token lacked `read:packages`, its scope-refresh endpoint returned HTTP 500 twice, and the Git Credential Manager credential received HTTP 401 for the plugin POM. These credentials were not saved as Gradle configuration. The user then supplied a classic PAT as `GRADLE_PAT` in the workspace `.env`; the corrected setup script verified the real plugin POM and saved the credential in the user-level Gradle properties file. Gradle now reads that file automatically; future builds do not need to load `.env`.

## Sources

- [Morphe development setup](https://github.com/MorpheApp/morphe-documentation/blob/main/docs/morphe-development/1_setup.md): user-level `gpr.user` / `gpr.key` and `read:packages`.
- [GitHub Gradle registry authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry): classic PAT requirements.
- [Gradle build environment](https://docs.gradle.org/current/userguide/build_environment.html): user-level property files, precedence, environment providers, and `GRADLE_USER_HOME`.
