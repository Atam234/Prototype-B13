# ZeroInstall Relay

ZeroInstall Relay is an Android prototype for sending a message from a phone with no signal. A nearby phone that has mobile data relays it, and that phone doesn't need to install anything.

```
 ┌────────────── Sender (offline) ──────────────┐            ┌──── Helper (has mobile data) ────┐
 │ Compose UI → AES-256-GCM → Room (PENDING)    │  Wi-Fi     │ Any browser                      │
 │ LocalOnlyHotspot  +  Ktor CIO :8080          │◄──────────►│ relay.html served by the Sender  │
 │   GET  /api/pending   → RELAYED_VIA_HELPER   │            │  1. GET  /api/pending            │
 │   POST /api/ack/{id}  → DELIVERED            │            │  2. POST cloud endpoint (data)   │
 │ WorkManager auto-sync (if Sender gets signal)│            │  3. POST /api/ack/{id}           │
 └──────────────────────────────────────────────┘            └──────────────────────────────────┘
```

## Project layout

| Path | Purpose |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/` | Project-level build: AGP 8.7.3, Kotlin 2.0.21, KSP, Gradle 8.10.2 wrapper |
| `app/build.gradle.kts` | Dependencies: Ktor 2.3.12 (CIO, content negotiation, CORS), Compose BOM, Room, WorkManager, ZXing, coroutines |
| `app/src/main/AndroidManifest.xml` | Permissions, `usesCleartextTraffic="true"`, `connectedDevice` foreground service |
| `service/RelayForegroundService.kt` | Holds the LocalOnlyHotspot reservation, the Ktor server, and the wake and Wi-Fi locks |
| `service/RelayStateHolder.kt` | Shared runtime state and activity log (service, server and UI) |
| `server/LocalRelayServer.kt` | Ktor routes, CORS, per-session token auth, status transitions |
| `assets/relay.html` | Helper portal: vanilla JS relay with a localStorage cache, auto-retry, **Retry Relay** button and live log |
| `hotspot/HotspotController.kt`, `hotspot/WifiQr.kt` | LocalOnlyHotspot wrapper (API 26–34 differences) and `WIFI:` QR payloads |
| `crypto/PayloadCrypto.kt` | End-to-end encryption (PBKDF2-SHA256 → AES-256-GCM, recipient bound as AAD) |
| `data/*` | Room entity, DAO and repository; SharedPreferences for the cloud endpoint |
| `sync/AutoSyncWorker.kt` | Periodic and on-demand direct upload once the Sender has validated internet |
| `net/*` | Cloud envelope (shared format with the JS), HttpURLConnection uploader, interface/IP discovery |
| `ui/*` | Compose screen, ViewModel, QR rendering, theme |

## Build the debug APK

1. Install **Android Studio Ladybug (2024.2.1) or newer**. Its bundled JDK 17+ is fine.
2. Use **File → Open** and select this folder. Let Gradle sync. Android Studio downloads Gradle 8.10.2, AGP and the dependencies, and offers to install **SDK Platform 34** if you don't have it.
3. Use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   You can also build from a terminal: `./gradlew assembleDebug`. On Windows, use `gradlew.bat assembleDebug`.
4. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
   Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or run ▶ from Android Studio.

To run the unit tests (crypto round-trip and tamper checks, Wi-Fi QR escaping): `./gradlew testDebugUnitTest`.

## Test with two phones

**Phone A (Sender).** Install the APK. Enable airplane mode, then turn Wi-Fi back on (the switch can stay off; hotspot works either way). This simulates "no signal".

1. Enter a recipient ID, a message and a shared secret (at least 6 characters), then tap **Encrypt & Queue Message**. The message shows as `PENDING`.
2. Optionally set the cloud endpoint. It defaults to `https://httpbin.org/post`. A `https://webhook.site/<your-id>` URL lets you see the payloads arrive.
3. Tap **Start Relay Hotspot** and grant the permission prompts: *Nearby Wi-Fi devices* on Android 13+, *Location* on Android 8–12 (Location must also be switched on). The screen then shows the SSID, password, security type, a Wi-Fi QR code, and the relay URL with its own QR code.

**Phone B (Helper).** Use any phone with mobile data. It doesn't need the app.

1. Scan the **Wi-Fi QR code** with the camera, or join the SSID manually. When the phone says *"No internet"*, choose to stay connected.
2. Scan the **second QR code**, or type the URL shown (for example `http://192.168.97.1:8080`) into the browser.
3. The page fetches the pending messages, posts them to the cloud endpoint, and confirms back to the Sender. On Phone A the message moves through `PENDING → RELAYED VIA HELPER → DELIVERED (via HELPER <ip>)`.
4. If the page says it is *routing through the sender's Wi-Fi*, the Helper OS is sending all traffic over the hotspot. In that case:
   1. Turn Wi-Fi off and tap **Retry Relay**. The upload goes out over mobile data, from the cache on the page.
   2. Turn Wi-Fi back on, rejoin the hotspot, and tap **Retry Relay** again. This sends the confirmation to the Sender.

**Auto-sync fallback.** Leave a message `PENDING`, then turn airplane mode off on Phone A. Within a few seconds (or the next 15-minute periodic run), `AutoSyncWorker` uploads it directly and marks it `DELIVERED (via DIRECT_SYNC)`. **Sync now** schedules the upload immediately.

**If LocalOnlyHotspot is blocked.** Some carriers and OEMs block it, and the status line shows the reason. In that case:

1. Stop the relay.
2. Toggle **Use Portable Hotspot (manual)** and tap **Open hotspot settings**.
3. Enable the normal hotspot, and type its SSID and password into the app so the QR code matches.
4. Start again. The server binds `0.0.0.0:8080`, so it is reachable on the tethering IP (often `192.168.43.1`; newer Android versions randomize the subnet). The app lists every candidate URL it detects.

## Security model

- **End-to-end encryption.** Only ciphertext leaves the app, and only ciphertext is stored. The Helper and the cloud see an AES-256-GCM envelope. The key is derived from a secret shared with the recipient (PBKDF2-HMAC-SHA256, 120k iterations, random salt and IV per message). The recipient ID is authenticated as AAD, so ciphertext can't be re-addressed to another recipient. The plaintext is never stored, and the secret is not saved in Compose saved-state.
- **Local API access.** Every `/api/*` call needs `X-Relay-Token`, a random 192-bit token generated each time the server starts. The token is injected only into the portal HTML, so a third-party site the Helper visits can't read or acknowledge messages. Token comparison runs in constant time. The portal ships with a strict CSP, `no-store` caching and no referrer.
- **Duplicates.** Both delivery paths send the same `idempotencyKey` (the message UUID), so a backend should de-duplicate on it. A message can legitimately arrive twice, through the Helper and through direct sync.
- **Prototype limitations.**
  - The Sender trusts the Helper's acknowledgement. A malicious Helper could falsely mark a message delivered. In production, have the cloud return a signed receipt that the Sender verifies.
  - The hotspot traffic is plain HTTP inside the WPA2/WPA3-protected hotspot.
  - The shared secret has to be agreed with the recipient out of band.

## How this was verified

This repository was authored in a sandbox without access to the Android SDK download host, so the APK has **not** been built or run on a device. What was verified:

- **Compilation.** The non-UI core compiles against the real Ktor 2.3.12 artifacts and the Android API 34 framework jar: the Ktor server and routes, the data layer, crypto, networking, `HotspotController` and the state holder.
- **Unit tests.** The 8 unit tests pass.
- **End-to-end relay.** The real `LocalRelayServer` and the real `relay.html` were driven in headless Chromium against a mock cloud endpoint. Both messages went `PENDING → RELAYED_VIA_HELPER → DELIVERED`. The cloud received only ciphertext. Requests without a token got `401`. The CORS preflight was accepted. When the cloud endpoint was unreachable, the page showed the "turn Wi-Fi off" guidance and then recovered through auto-retry once the endpoint came up.
- **Not verified.** The Compose UI, the foreground service, the WorkManager worker and the Room database were reviewed by hand but not compiled, because they need Google Maven artifacts. The first Android Studio sync and build is their first compile.
