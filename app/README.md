# app

Purpose: the Remu Android app, one Kotlin and Jetpack Compose app (minimum SDK 26) with Cashier mode on the counter phone and Owner mode on the owner's own phone.

## Build and test

Run from `app/`, with JDK 17 or newer (JDK 19 is used on the development Mac):

    ./gradlew assembleDebug                 # debug APK
    ./gradlew test                          # JVM unit tests and Robolectric Compose UI tests
    ./gradlew lint                          # Android lint; a missing Hindi string is an error
    ./gradlew assembleDebugAndroidTest      # compiles the instrumented tests
    ./gradlew connectedAndroidTest          # needs a device or an emulator (API 26+)
    maestro test maestro/cashier_enrol.yaml # see the header of each flow for its -e variables

`local.properties` (gitignored) holds `sdk.dir`. The bundled Inter and Noto Sans Devanagari fonts (from Google Fonts, static weights 400 to 700) are under the SIL Open Font License; the licence texts are in `licenses/`. Versions: Gradle 8.7 (wrapper), Android Gradle Plugin 8.5.2, Kotlin 1.9.24 with the Compose compiler 1.5.14, Compose BOM 2024.06.00, compileSdk and targetSdk 34, minSdk 26. Every library version is pinned in `gradle/libs.versions.toml`.

`verifyHindiStrings` runs before every build and in `check`: it fails when a key in `res/values/strings.xml` has no counterpart in `res/values-hi/strings.xml`. Lint's `MissingTranslation` is also an error, and `StringResourcesTest` checks parity plus the v1.0 consent texts byte for byte.

## Configuration

Build configuration values come from a `-P` Gradle property, then an environment variable, then `local.properties`, then the default:

| Name | Default | Use |
|---|---|---|
| `REMU_CLOUD_URL` | `http://10.0.2.2:3000` (the dev machine from the emulator) | cloud routes, docs/API_CONTRACT.md section 4 |
| `SUPABASE_URL` | `http://10.0.2.2:54321` | Supabase Auth sign-in for Owner mode |
| `SUPABASE_ANON_KEY` | empty (sign-in shows "not set up") | Supabase public anon key |
| `SENTRY_DSN` | empty (Sentry disabled) | error tracking with the before-send scrubber |

Cleartext http is allowed only in debug builds and only to `10.0.2.2`, `localhost` and `127.0.0.1` (`src/debug/res/xml/network_security_config.xml`).

## Firebase (FCM)

`google-services.json` comes from the Firebase console (project settings, Android app `com.remu.app`) and goes at `app/app/google-services.json`. It is gitignored and never committed. The Google Services Gradle plugin is applied only when that file exists, so the build passes without it; `BuildConfig.FIREBASE_CONFIGURED` then stays false and FCM registration is skipped. Owner mode still gets alerts over the LAN WebSocket.

## Structure (docs/ARCHITECTURE.md section 3.2)

    app/app/src/main/java/com/remu/app/
    ├── RemuApp.kt, MainActivity.kt, AppContainer.kt   hand-wired dependencies, one per process
    ├── core/      theme (DESIGN.md tokens, Inter and Noto Sans Devanagari bundled), ui components,
    │              format (rupees, dates, validation), nav, locale, observability (Sentry scrubber)
    ├── data/      session/ (Keystore AES-GCM encrypted DataStore), state/ (staff session, hub
    │              status, connectivity), alerts/ (in-memory alert store, FCM and WebSocket ingest)
    ├── network/   hub/ (HubClient: pinned HTTPS, WebSocket, mDNS), cloud/ (CloudClient, Supabase
    │              Auth), fcm/ (messaging service, token registration)
    └── feature/   login/, pairing/, enrol/, members/, alerts/, timeline/, export/, settings/,
                   setup/, owner/
    app/app/src/test/          JUnit, Robolectric and Compose UI tests against MockWebServer
    app/app/src/androidTest/   instrumented tests (privacy cache check, login, real-hub e2e)
    app/maestro/               Maestro flows for TASK-053 and TASK-084

One ViewModel and one UI state class per screen. Screens never touch OkHttp. There is no database: the app keeps the session, the mode and per-phone preferences in one encrypted DataStore value and everything else in memory.

## Security and privacy as built

- Hub HTTPS: `FingerprintTrustManager` accepts only the hub leaf whose SHA-256 fingerprint was stored at pairing; a changed certificate returns `CertificateChanged` and the screens say "pair again". Pairing records the presented leaf and refuses it unless it equals `cert_sha256` in the `POST /pair` reply. The cloud uses the system trust store with no pin.
- Secrets: the hub token, fingerprint and Supabase tokens are in `files/datastore/session.enc`, AES-256-GCM with a non-exportable Android Keystore key. Backups are disabled.
- Face crops: JPEG in memory, under 300 KB, wiped after the hub replies, never written to disk. OkHttp runs with no cache. Tests check that no image-like file is left in app storage (Robolectric and on device).
- FCM: only `shop_id`, `visit_id` and `member_id` are read. The card is fetched from the hub on the shop WiFi or the cloud off it, and the notification is built on the phone. The lock screen shows "Member at the gate" only.
- Sentry: off unless `SENTRY_DSN` is set. `send_default_pii` is off, screenshots and view hierarchy are off, and `SentryScrubber` removes the user, the request, phone numbers, e-mail addresses, tokens, base64 blobs and every field named like phone, name, notes, items, vec, landmarks, image, crop, token or key.
- `FLAG_SECURE` is set on the alert, member, member search and household screens. Clips play from memory over the LAN only.

## Assumptions (to fold into docs/ARCHITECTURE.md and docs/DECISIONS.md in the phase documentation tasks)

- Supabase Auth needs `SUPABASE_URL` and `SUPABASE_ANON_KEY` in build configuration besides `REMU_CLOUD_URL`. The anon key is public by design.
- The contract has no route that lists the owner's shops. The shop id comes from `POST /api/shops` or `POST /pair`. A signed-in phone with neither reads `rest/v1/shops` through Supabase PostgREST, scoped by RLS.
- `GET /clips/{id}`: the LAN visit row has `has_clip` but no clip id, so the app requests `/clips/{clip_id}` when the hub sends `clip_id` and `/clips/{visit_id}` otherwise. The hub should accept the visit id, or add `clip_id` to the visit row.
- The hub has no alert-card route, so an FCM push on the shop WiFi builds the card from `GET /members/{id}` and `GET /visits`.
- Setup routes not shaped by the contract: `GET /discover` returns `{"streams":[{"id","label","url"}]}`, `PUT /settings/streams` takes `{"gate_url"}`, and `GET /preview` is multipart MJPEG.
- The counter phone's invite QR encodes `remu://pair?host=<hub address>&code=<invite code>`.
- The setup wizard asks for the owner's PIN (`POST /staff`, role owner) right after pairing when the hub has no owner yet.
- Owner mode with the hub out of reach: the hub cannot check a PIN, so a phone paired as owner with a valid cloud session can continue using the last owner staff id. Cashier mode has no such path. Owner mode asks for the PIN again after 15 minutes idle.
- Phone numbers go to the hub as E.164 (`+919876543210`).
- Make member waits up to 8 seconds for the hub. The camera stays live meanwhile.
- The "skip face" count is kept on the phone (`skipFaceCount`). The contract has no route that reports it to the cloud yet (TASK-127).
- A household's extra adults may each have their own name and phone in the form. They fall back to the top-level values.
- Owner settings show shop name and city read-only, because the contract has no shop update route. Reset PIN and remove staff are not built, because the contract has no route for them.
- OPEN items resolved provisionally: lock-screen text is "Member at the gate" only, and the Hindi button labels are "स्वागत किया" and "ये नहीं" as proposed in docs/DESIGN.md. The app icon is a placeholder until a wordmark exists.
