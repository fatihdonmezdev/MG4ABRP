# Publishing an OTA release

How to ship an update the car will install by itself. Written for whoever does this
next — probably me, having forgotten the details.

## What the updater actually looks for

`OtaUpdater.check()` reads `https://api.github.com/repos/fatihdonmezdev/MG4ABRP/releases`
and walks **every** entry, keeping the highest version rather than the first match. A
release is a candidate only if all of these hold:

| Requirement | Where it is enforced | What happens otherwise |
|---|---|---|
| Marked **pre-release** | `release.optBoolean("prerelease")` | Skipped entirely |
| Asset name contains `unstable` | `name.contains("unstable")` | Asset ignored |
| Asset name ends `-<version>.apk` | `ASSET_VERSION` regex | No version, asset ignored |
| Version strictly higher than installed | `isNewer()` | Nothing to do, reports "Güncel" |
| Download host on the allowlist | `isAllowedUrl()` | Refused and logged |
| APK signed with the **same certificate** as the running app | `signatureMatchesRunningApp()` | Deleted, never installed |

The last one is the one that bites. The car runs a platform-signed build; an APK signed
with a debug key — or anyone else's key — is downloaded, rejected and deleted. Sign every
release with the same platform key or the update silently never lands.

The version comes from the **asset filename**, not the git tag. The tag can stay
`unstable` forever and be moved; the filename is what identifies a build.

## Release checklist

1. **Raise the version.** `app/build.gradle`, `defaultConfig`:

   ```gradle
   versionCode 20          // must increase — Android orders updates by this, not by name
   versionName "2.2.3"
   ```

   The unstable flavour appends its own suffix, so `2.2.3` becomes `2.2.3.0-unstable`.
   Check what the installed build reports before picking a number: the app's About
   dialog shows it, and `isNewer` compares numerically segment by segment.

2. **Build.**

   ```bash
   ./gradlew :app:assembleUnstableDebug
   ```

3. **Sign with the platform key.** Options come *before* the input APK — apksigner
   rejects the other order.

   ```bash
   BT=~/Android/Sdk/build-tools/35.0.0
   KEYS=~/Desktop/Coding/LUMINA/DriveHub_Kamera/tools

   "$BT/zipalign" -f 4 \
     app/build/outputs/apk/unstable/debug/app-unstable-debug.apk aligned.apk

   "$BT/apksigner" sign \
     --key  "$KEYS/platform.pk8" \
     --cert "$KEYS/platform.x509.pem" \
     --out  FatihsMG4-unstable-2.2.3.apk \
     aligned.apk

   rm aligned.apk
   ```

   The output filename **is** the version the updater reads. Name it
   `FatihsMG4-unstable-<version>.apk` and nothing else.

4. **Verify the signer** before uploading. It must say `CN=Android`:

   ```bash
   "$BT/apksigner" verify --print-certs FatihsMG4-unstable-2.2.3.apk | grep DN:
   ```

5. **Publish.** On github.com/fatihdonmezdev/MG4ABRP → Releases → Draft a new release:
   - tag: `unstable` (reuse it; the rolling tag is the design)
   - **tick "Set as a pre-release"** ← the whole thing is inert without this
   - attach the signed APK

6. **Install it.** Open the app on the head unit and press the refresh button in the top
   bar. Expected toast: `Güncellendi: 2.2.3 — yeniden başlatın`.

## When it says something else

| Toast | Meaning |
|---|---|
| `Güncel (2.2.2.0-unstable)` | No release beat the installed version. Check the pre-release tick and the asset filename. |
| `İndirme başarısız` | Release found, download did not complete. Usually no network. |
| `Kurulum reddedildi (imza uyuşmuyor?)` | Signed with the wrong key — rebuild and re-sign with `platform.pk8`. |
| `Güncelleme kontrolü başarısız` | The check threw. `adb logcat -s EVABRP.Update OtaUpdater` if a cable is available; otherwise the in-app Log page. |

## Untested

`OtaUpdater.install()` shells out to `/system/bin/pm install -r`, which only a
platform-signed app may call. That is what this build is, so it should work — but it has
never actually run on the car. If installs are refused with a signature that *does* match,
the fallback is an `ACTION_INSTALL_PACKAGE` intent, which asks the driver to confirm
instead of installing silently.

## Why the check is manual

The refresh button is the only trigger. `UpdateHook.checkInBackground(this)` also runs on
app start, but the app is rarely launched — the upload service does the work, and the
driver does not open the UI on every trip. The button is there so an update can be pulled
deliberately rather than waiting for a launch that may not come.

The upload service does **not** check for updates on its tick. Installing a new APK
restarts the process, and doing that mid-drive would interrupt the telemetry the service
exists to send.
