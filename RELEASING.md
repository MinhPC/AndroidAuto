# Releasing a new version

The launcher updates itself from the newest GitHub Release of this repository
(`UPDATE_BASE_URL` in `gradle.properties`). Each release must contain two assets:
`update.json` and `CarLauncher-<version>.apk`.

1. In `app/build.gradle.kts` raise **both** `appVersionCode` (must increase every release) and `appVersionName`.
2. Write what changed in `release-notes.txt` (UTF-8, shown in the app next to the update button).
3. Run `./gradlew publishUpdateFiles`. It builds the signed release APK and writes
   `app/build/update/update.json` and `app/build/update/CarLauncher-<version>.apk`.
4. Commit, then tag and push: `git tag v<version>` and `git push origin main v<version>`.
5. On GitHub: Releases → *Draft a new release* → choose tag `v<version>` → attach **both** files from
   `app/build/update/` → *Publish release*. (It must be a normal release, not a draft or pre-release,
   or `releases/latest` will not point to it.)

## Signing key

An update only installs if it is signed with the same key as the installed app. Release builds are signed
with the local debug keystore (`~/.android/debug.keystore`); back that file up. Building from another
machine or losing it means devices must uninstall the app before the next update installs.
