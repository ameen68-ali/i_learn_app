# Implementation evidence

Date: 2026-11-24 (Asia/Baghdad)

This report separates implementation and automated results from device, provider, and educator verification. The complete release gates in ../todo.md remain authoritative.

## Environment and baseline

- JDK 17.0.12; Android SDK at C:/Android/sdk, platform 34/build tools 34 available.
- Existing cached Gradle 8.12 was used to establish a successful unmodified application build (`:app:assembleDebug`, exit 0, 2m24s).
- Added official Gradle 8.12 wrapper with published distribution SHA-256 `7ebdac923867a3cec0098302416d1e3c6c0c729fc4e2e05c10637a8af33a76c5`.
- Two overlapping initial Gradle checks caused generated Kotlin cache corruption. Both ended; a clean, sequential in-process Kotlin compilation succeeded. Subsequent verification is sequential.
- No device was attached at the initial baseline; an API 34 physical device (model 2406ERN9CC) subsequently connected. The production package `com.ilearn.app` is already installed, so testing uses the separate `com.ilearn.app.validation` package and never replaces the production installation.
- A large Robolectric native dependency download was too slow to complete. Database regression cases were retained and moved to Android instrumentation, where they exercise real SQLite. They are not counted as executed unit tests.

## Implementation present

- Additive source columns; existing local IDs remain intact. Update-in-place parent/lesson writes prevent cascade deletion during refresh.
- Source-aware course deletion; remote courses do not delete media folders. Playlist-item IDs preserve repeated videos and reordered entries.
- Paginated YouTube metadata import/preview, refresh, official embedded playback, and common learning tools.
- API profiles for Chat Completions-compatible endpoints, Responses, Anthropic Messages, and Gemini. No mandatory provider.
- Session-only credentials by default, optional AES-GCM/Keystore persistence, no-backup credential/history storage, HTTPS, redirect rejection, and no credential bridge to WebView.
- Attached/local transcript parsing/retrieval, English/Arabic/bilingual prompts, validated source references, explicit general-help mode, and cancellable conversations.
- Backup version 2 includes remote identities before dependent progress/notes; version 1 local-backup reading retained.

## Current verification

- Clean application-component compilation: exit 0.
- Integrated application compilation: exit 0.
- Full validation build: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PvalidationBuild=true`, exit 0.
- 21 unit tests passed, zero failures/skips. They cover trusted playlist URLs, duration parsing, caption fidelity/coalescing, neighboring evidence/order, Arabic/English bilingual instructions, reference validation, four protocol codecs, output-limit handling, credential validation, TLS HTTP/1.1 fragmented Arabic SSE, HTTP/2 SSE, missing completion events, cancellation, and redirect credential isolation.
- Lint: zero errors and 33 warnings (including dependency/target-version notices and existing locale/resource warnings); no baseline or disabled checks was added. Corrected API 24 path checking, explicit Media3 opt-in declarations, and audio sample-flag mapping rather than suppressing errors.
- Validation APK signature verified with Android build-tools apksigner. Artifact: `artifacts/iLearn-validation.apk`, 19,667,996 bytes, SHA-256 `AEE7C9781F4198ED084971D19D9B8962875AC65D36FF6E8EC6CF60A833D18427`.
- Two connected-test attempts were blocked before executing any tests by Android `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. The user unlocked the device after the first attempt; the second still hit the USB-install restriction. No application assertions ran, and these attempts are not counted as passes.
- Validation and test APKs were placed in the phone's Downloads folder for normal user-approved installation. Device tests remain pending until installation succeeds. The production package and database have not been replaced or copied.

## External evidence still required

- Run the eight compiled instrumentation cases on Android: migration/refresh/backup round trips, session/encrypted key storage, conversation isolation, two tutor/settings UI cases, and a live official YouTube embed/progress/note case.
- Signed-device WebView/YouTube embedding, fullscreen, seeking, ads, process recreation, and API 24/current-Android coverage.
- Real YouTube Data API project credentials/quota/restriction checks and a multi-page playlist trial.
- Real-provider smoke trials for each advertised API family, with the user's chosen endpoint/model/key.
- Human review of the 48-question pilot and a documented credential/distribution review.

No real API credentials were requested, discovered, used, recorded, or committed during these implementation checks. Optional provider-native video analysis remains unavailable until separately verified; transcript-grounded tutoring is the baseline. Offline downloads are excluded.
