# NOVA GHOST FAMILY

Consent-based parental-control app for Android (Kotlin · Jetpack Compose · Material 3 · Room · WorkManager · Firebase).
Midnight-black + neon cyan/violet glass UI with an animated **Ghost Core** dashboard and a **Ghost Command** palette.

**Principles:** nothing is hidden, the child controls what is shared, every sensitive action is checked on the server,
and features that Android does not officially allow are not faked.

## Status — read this first
* The project was written without access to a Gradle/Android toolchain, so **it has not been compiled yet**.
  The first GitHub Actions run is the compile check. If it reports errors, send them back and they get fixed.
* `gradle/wrapper/gradle-wrapper.jar` (a binary) is not included. The workflow regenerates it automatically
  (`gradle wrapper --gradle-version 8.7`). To make it permanent locally: `gradle wrapper --gradle-version 8.7` and commit the jar.
* Without `app/google-services.json` the app builds and runs in **"backend not configured"** mode:
  accounts, rooms, push, remote reminders and screen sharing are OFF (the UI says so). **Local mode** works on-device:
  UsageStats dashboard, your own limits/bedtime/study alerts, notification permissions, optional device-admin lock.

## Features → implementation
| Feature | Where |
|---|---|
| Parent/Child roles, rooms, QR + expiring codes (15 min, single use, server-generated) | `functions/index.js` (`createInvite`, `redeemInvite`), `ui/Root.kt`, `ui/Screens.kt` |
| Authorization on the server | `firestore.rules` + Cloud Functions (never trusts the client) |
| Usage dashboard (UsageStatsManager), daily/weekly, gaming/entertainment, battery, last seen | `data/UsageRepo.kt`, `ui/Screens.kt` |
| Actual vs delayed vs unavailable data | `Fmt.freshness`, `FreshnessChip` |
| Goals, gaming limit, bedtime, study (alerts at 80 % / 100 %) | `data/PolicyEngine.kt`, `work/UsageSyncWorker.kt` |
| Reminders: now/scheduled, delivery status, history, FCM | `functions/index.js` (`onReminderCreated`, `dispatchScheduled`), `push/NovaMessagingService.kt` |
| Screen sharing: MediaProjection consent, foreground notification + STOP, WebRTC | `share/ScreenShare.kt` |
| Permissions dashboard (child toggles, parent read-only) | `ConsentScreen` |
| Device Admin (lock) / Device Owner (suspend games) | `admin/DeviceControl.kt` |
| Audit log (append-only), unlink, delete room/data | rules + `unlinkDevice` / `deleteRoom` functions |
| Themes, reduce motion, large text, high contrast | `ui/Theme.kt`, Settings |
| Loading / empty / offline / permission-denied / error states | `ui/Components.kt` |

## Honest limits
* **Enforcement:** Android does not let normal apps block other apps. This app alerts (≈15-min granularity because
  WorkManager's minimum period is 15 min), can lock the screen if the user activates Device Admin, and can suspend games
  only if the device is a provisioned **Device Owner**. Installing the APK never grants admin rights.
* **Usage data** comes from `UsageStatsManager`; Android keeps limited history and totals may differ slightly from Digital Wellbeing.
* **Screen sharing** needs a network path: STUN is configured; add a TURN server in `Rtc.config()` for strict NATs.
  It is always started by the child accepting Android's capture dialog, shows an ongoing notification, and stops instantly.
  No microphone or camera access exists in this app.
* Cloud Functions need the Firebase **Blaze** plan.

## Firebase setup (exact steps)
1. Firebase console → create project → **Add Android app**, package `com.novaghost.family` → download `google-services.json` → save as `app/google-services.json` (git-ignored).
2. **Authentication** → Sign-in method → enable **Email/Password**.
3. **Firestore Database** → create (production mode).
4. Install the CLI: `npm i -g firebase-tools && firebase login && firebase use --add`.
5. `cd functions && npm install && cd ..`
6. `firebase deploy --only firestore:rules,firestore:indexes,functions`
7. Rebuild the app. Cloud Messaging is enabled automatically for the project.
8. For CI builds that need Firebase, store the file as a repository secret `GOOGLE_SERVICES_JSON` and add this step before the build in
   `.github/workflows/android.yml`: `echo '${{ secrets.GOOGLE_SERVICES_JSON }}' > app/google-services.json`.
   Without it CI still produces a working "local mode" APK.

No private keys or service-account files belong in the APK or the repo. Functions use the platform's built-in credentials.

## Build
```
./gradlew testDebugUnitTest assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and Android SDK 34 (`platforms;android-34`, `build-tools;34.0.0`).

## GitHub Actions
`.github/workflows/android.yml` runs on pushes to `main` and manual dispatch: JDK 17 → `android-actions/setup-android@v3`
(installs cmdline-tools + SDK packages, sets `ANDROID_HOME`) → Gradle 8.7 → ensure wrapper → unit tests → `assembleDebug`
→ verify the APK exists → upload artifact **NovaGhostFamily-debug-apk**.

## Pairing flow
Parent: sign up → create room → Family → *New invitation* (QR + code).
Child: sign up as child → scan/enter code → everything starts OFF → Consent tab → child enables usage/reminders/downtime/screen share.

## Managed devices (optional, advanced)
Device Owner can only be set on a freshly reset device before any account is added:
`adb shell dpm set-device-owner com.novaghost.family/.admin.NovaAdminReceiver`
(or via QR/zero-touch enrollment with a hosted APK). Do this only on devices you own and with the child's knowledge.
Device Admin (screen lock only) can be activated by the child in Consent → Device admin.

## Tests
`app/src/test/.../CoreLogicTest.kt` covers usage math, policy/alert engine, bedtime windows, invite parsing,
freshness classification, policy/consent serialization.
