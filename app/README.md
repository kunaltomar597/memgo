# app

Purpose: the Remu Android app, one Kotlin and Jetpack Compose app (minimum SDK 26) with Cashier mode on the counter phone and Owner mode on the owner's own phone. Nothing here yet; TASK-004 (Phase 0) creates the Gradle project.

## What will live here

Cashier mode: CameraX preview with an ML Kit box on every face, the largest face pre-selected, household grouping, fields for phone, bill amount, items, occasion and notes, the script line on screen in Hindi and English, one "Make member" button, member search, remove member, an offline indicator. The app sends an aligned crop with landmarks to the hub and never computes an embedding or keeps a crop. Owner mode: alerts by Firebase Cloud Messaging and the LAN WebSocket, the alert card with "Greeted" and "Not them", the member card with purchases, notes, visit timeline and clips (LAN only), settings, staff PINs, device status, consent export, Hindi or English.

## Planned structure (docs/ARCHITECTURE.md section 3.2)

    app/app/src/main/java/com/remu/app/
    ├── core/      theme, navigation; strings in res/values/ and res/values-hi/
    ├── data/      repositories and in-memory state; DataStore for session and mode; no database in the app
    ├── network/   hub/ (HubClient: pinned OkHttp, Retrofit, WebSocket), cloud/ (CloudClient), fcm/
    └── feature/   login/, pairing/, enrol/, members/, alerts/, timeline/, export/, settings/
    app/app/src/test/ (JUnit), app/app/src/androidTest/ (Compose UI), app/maestro/ (Maestro flows)

One package per screen with its composables, one ViewModel and one UI state class. The app has no database of its own (no Room, no SQLiteOpenHelper): screens hold what they need in memory, DataStore keeps only the session and the mode, and everything else is fetched again from the hub or the cloud. docs/RULES.md and docs/ARCHITECTURE.md section 3.2 say the same.

## Commands

`cd app && ./gradlew assembleDebug`, `./gradlew test`, `./gradlew lint`, `./gradlew connectedAndroidTest` (needs a device or an emulator). `REMU_CLOUD_URL` goes into build configuration per flavour; `google-services.json` comes from the Firebase console and is never committed.

## Created by

TASK-004 (Phase 0: Project setup) creates the Gradle project with an empty Compose screen and passing lint and tests. Phase 3 builds Cashier mode. Phase 5 builds Owner mode. OPEN: the applicationId is assumed to be `com.remu.app` until the founder confirms the domain.
