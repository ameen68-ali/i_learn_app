# Task checklist: YouTube courses and provider-independent tutor

Status: Implementation is present. Automated build, lint, and unit checks have passed; physical-device checks are underway. Unchecked criteria still require acceptance evidence and must not be treated as completed simply because code exists. Scope and release targets: [plan.md](plan.md). Actual results: [evidence](evidence/implementation.md).

| Tasks | Implementation / evidence status |
| --- | --- |
| T01 | Wrapper/toolchain/test setup complete; baseline build recorded. |
| T02 | API 34 device now available; isolated validation build prepared. Live API credentials are not yet supplied. |
| T03–T04 | Source migration, safe updates/deletion, playback sessions present; device migration/lifecycle checks underway. |
| T05–T08 | Playlist metadata/identity/refresh, embedded playback/fullscreen and learning controls present; live device check underway. |
| T09–T13 | Four protocol families, custom endpoints, secure profiles, and TLS stream/cancellation/redirect fixtures implemented. Actual provider/model certification remains open. |
| T14–T17 | Transcript ingestion, coherent passages/context, scoped history, language modes, source references, and tutor UI present; local fixtures pass, device UI checks underway. |
| T18 | Optional native-video analysis remains unavailable until a selected provider/model is verified; no provider is forced. |
| T19 | Backup v2 plus v1 reader and device round-trip regression cases present; physical SQLite verification underway. |
| T20 | Automated review performed; device, real-service, distribution, and educator signoff must be recorded separately. |

Paths beginning `data/`, `player/`, `youtube/`, `tutor/`, or `ui/` below are relative to `android/app/src/main/java/com/ilearn/app/`. New filenames are proposed. Test files belong under `android/app/src/test/` or `android/app/src/androidTest/` as appropriate. Keep each slice to roughly five implementation/test files; split it before coding if it grows beyond a focused session. Wrapper-generated files are one tooling unit.

## M0 — Baseline and feasibility

### T01 — Reproducible build and baseline checks

- [x] Verify JDK 17, Android SDK, compatible Gradle, and device availability; create/check Gradle wrapper with distribution checksum.
- [ ] Add unit/instrumentation test dependencies and a migration fixture; capture the untouched app's build/lint results and existing failures.
- [ ] Record baseline local-course smoke results; do not hide an existing failure by disabling checks.

Verification: Run the four commands in plan section 9 when the required device exists; otherwise record device verification pending. Dependencies: none. Scope: M; wrapper, `android/app/build.gradle.kts`, baseline tests, evidence file.

### T02 — External capability and credential feasibility

- [ ] Demonstrate official YouTube embed playback and identity/origin behavior on a signed Android device; confirm playlist API credential restrictions and quota setup.
- [ ] Confirm request/authentication behavior of the four planned API families; document direct BYOK limitations and release constraints with no real secrets in evidence.
- [ ] Identify optional video-input support by exact provider/model; failure of that optional probe must not block transcript-based provider independence.

Verification: Record actual device/API outcomes, test profiles without secrets, and unresolved constraints. Dependencies: T01. Scope: M; narrow probe harness and `tasks/evidence/feasibility.md`. Remove disposable probe code or retain only useful regression coverage before milestone completion.

### G0 — Feasibility checkpoint

- [ ] Build baseline/toolchain documented; embed proven; API transport and credential decisions recorded.
- [ ] Distinguish executed checks from planned/unavailable checks before implementing dependent work.

## M1 — Shared foundations

### T03 — Source-aware persistence without data loss

- [ ] Add explicit local/YouTube source models and additive schema changes while preserving local IDs, notes, progress, bookmarks, and audio/subtitle paths.
- [ ] Add source-aware deletion: remote courses perform no local filesystem deletion; local deletion accepts only a verified owned directory.
- [ ] Verify fresh creation and supported-version migrations, including version 3; never use table replacement or parent-row `REPLACE` for remote updates.

Verification: Migration and deletion instrumentation tests pass. Dependencies: G0. Scope: M; `data/Models.kt`, `data/CourseDatabaseHelper.kt`, `data/CourseRepository.kt`, migration/deletion tests.

### T04 — Shared lesson playback session

- [ ] Define playback state/commands/capabilities and wrap the existing Media3 controller without changing local playback behavior.
- [ ] Bind active lesson, navigation, progress callbacks, and lifecycle cleanup to stable IDs; reject late events from prior sessions.
- [ ] Prove local resume, notes, bookmarks, fullscreen, subtitles, speed, and Arabic audio remain usable.

Verification: Session transition tests and local device smoke pass. Dependencies: T03. Scope: M; `player/LessonPlaybackSession.kt`, existing controller, `MainActivity.kt`, `VideoPlayerScreen.kt`, session tests. Split UI follow-up if required.

### G1 — Foundation checkpoint

- [ ] Database preservation tests, local regressions, build, and focused lint pass.

## M2 — YouTube courses

### T05 — Playlist URL and metadata client

- [ ] Parse supported YouTube playlist/share URLs through an exact host allowlist; reject arbitrary URLs and missing playlist IDs.
- [ ] Fetch preview and all item pages with cancellation, quota/auth/error handling, normalized metadata, and no API secrets in logs.
- [ ] Represent unavailable entries and duplicate-video playlist items without guessing identities from titles/order.

Verification: URL and paginated transport fixture tests; one real playlist preview. Dependencies: T03, G0. Scope: M; `youtube/PlaylistUrlParser.kt`, `youtube/YouTubeMetadataClient.kt`, `youtube/PlaylistModels.kt`, client tests.

### T06 — Import and refresh a course transactionally

- [ ] Add playlist preview/import/refresh actions to the course library with loading, cancellation, retry, and empty/unavailable states.
- [ ] Upsert by stable remote identity and update metadata/order without replacing parents or losing user records; commit only a complete reconciliation.
- [ ] Reimport does not duplicate the course; add/reorder/remove and interrupted refresh preserve notes, bookmarks, and progress.

Verification: Reconciliation database tests and import UI/device flow. Dependencies: T05. Scope: M; `youtube/PlaylistCourseService.kt`, database/repository changes, catalog screen, reconciliation tests.

### T07 — Official embedded playback

- [ ] Implement a YouTube playback adapter with readiness, play/pause/seek/state/error support and a minimal validated WebView bridge.
- [ ] Honor player branding/controls and Android identity/origin requirements; prevent arbitrary navigation or native credential access.
- [ ] Handle embed restrictions, unavailable videos, connectivity errors, and external YouTube fallback.

Verification: Signed-device playback/error/lifecycle checks and adapter state tests. Dependencies: T04, T06. Scope: M; `youtube/YouTubePlaybackAdapter.kt`, `youtube/YouTubePlayerView.kt`, embedded page asset, adapter tests.

### T08 — Common learning tools on YouTube lessons

- [ ] Wire the adapter into lesson navigation and source-specific controls; hide unsupported local dubbing/extraction controls.
- [ ] Save correct lesson progress/resume/bookmarks and timestamped notes across next/back, rotation, backgrounding, and process recreation.
- [ ] Test seeking/resume within the proposed two-second tolerance after readiness in ad-free test segments; errors/ad states must not corrupt progress.

Verification: End-to-end signed-device flow plus stale-event regression tests. Dependencies: T07. Scope: M; `MainActivity.kt`, player/detail screens, session integration, instrumentation tests.

### G2 — YouTube checkpoint

- [ ] Add → preview → import → watch → note → exit → resume → refresh succeeds on device.
- [ ] Pagination, duplicates, inaccessible videos, refresh failure, and existing local playback verified.

## M3 — User-selected AI APIs

### T09 — Secure provider profiles

- [ ] Implement multiple named profiles with protocol, HTTPS endpoint, model, masked credential, active profile, session-only default, and opt-in encrypted persistence.
- [ ] Bind secrets to endpoint/profile; reject unsafe redirects; support key replacement/deletion and confirmed endpoint changes without leaking credentials.
- [ ] Provide settings and permission/privacy text; exclude secret storage from JSON/OS backup and device transfer immediately when persistence is introduced.

Verification: Profile isolation, endpoint-change, credential deletion, backup-exclusion, and Keystore failure tests. Dependencies: G0. Scope: M; `tutor/ProviderProfileStore.kt`, `tutor/CredentialStore.kt`, settings screen, backup rules/manifest, tests. Split persistence/UI integration if necessary.

### T10 — Common provider contract and OpenAI-compatible Chat adapter

- [ ] Define normalized tutor messages, capabilities, response events, usage, and typed errors; isolate HTTP credentials from tutor context.
- [ ] Implement custom-endpoint Chat Completions requests with optional SSE streaming and non-stream fallback when configured.
- [ ] Handle partial streams, timeout/cancellation, denied models, auth/quota errors, and malformed responses without automatic generation retries.

Verification: Shared adapter contract tests and a real compatible-endpoint smoke check. Dependencies: T09. Scope: M; `tutor/TutorProvider.kt`, `tutor/ProviderTransport.kt`, `tutor/OpenAiChatAdapter.kt`, adapter tests.

### T11 — OpenAI Responses adapter

- [ ] Implement the Responses request/event/output shape behind the common contract, including text/refusal/incomplete/error states relevant to tutoring.
- [ ] Expose actual model capabilities and usage without assuming Chat Completions fields or shared stateful server conversations.
- [ ] Prove profile switching does not send a previous profile's credential, history, or endpoint-specific payload.

Verification: Contract fixtures and live smoke with an authorized profile; record live status. Dependencies: T10. Scope: M; `tutor/OpenAiResponsesAdapter.kt`, provider registry, adapter tests.

### T12 — Anthropic Messages adapter

- [ ] Implement native authentication/version headers, system/message formatting, streaming, usage, and error mapping.
- [ ] Support user-selected endpoint/model under the same tutor contract with capability-aware limits.
- [ ] Keep connection testing explicit and separate from sending lesson content.

Verification: Contract fixtures and live smoke with an authorized profile; record live status. Dependencies: T10. Scope: M; `tutor/AnthropicMessagesAdapter.kt`, registry, adapter tests.

### T13 — Gemini adapter as an optional provider choice

- [ ] Implement native text request/response/stream/error mapping under the same common contract.
- [ ] Do not select Gemini automatically or make transcript tutoring depend on its availability.
- [ ] Keep optional native-video capabilities separate from baseline text support and verify them by profile/model.

Verification: Contract fixtures and live smoke with an authorized profile; record live status. Dependencies: T10. Scope: M; `tutor/GeminiAdapter.kt`, registry, adapter tests.

### G3 — Provider checkpoint

- [ ] Four protocol adapters pass the shared contract tests; profile choice/custom compatible endpoint works.
- [ ] Every release-supported adapter has a documented live smoke result; unavailable credentials remain an explicit release-evidence gap.
- [ ] No unsupported API is advertised as universally compatible; new protocols can be added without changing tutor UI/retrieval.

## M4 — Grounded tutor

### T14 — Transcript ingestion and retrieval

- [ ] Read existing VTT/SRT and attach VTT/SRT/UTF-8 text to either lesson source through the document picker.
- [ ] Store bounded, validated passages with lesson/source/language/revision and optional timestamps; plain text receives no invented timestamps.
- [ ] Retrieve relevant passages plus current-moment context; reindex when source content changes and expose unavailable/malformed-source states.

Verification: Parser/retrieval fixtures for English, Arabic, bilingual text, multiline cues, malformed times, overlapping cues, and empty/large files. Dependencies: T03. Scope: M; `tutor/TranscriptParser.kt`, `tutor/LessonSourceStore.kt`, `tutor/LessonRetriever.kt`, source tests.

### T15 — Grounding contract and reference validation

- [ ] Build requests from selected lesson evidence, language, current timestamp, bounded history, and provider limits; never include secrets or unrelated lessons.
- [ ] Treat source text as untrusted data; require evidence references and resolve them only against the captured request's source revision.
- [ ] Detect absent/insufficient evidence, invalid references, truncation, and stale answers; label additional examples separately from source statements.

Verification: Prompt-injection, fabricated-ID, source-change, insufficient-context, language, and cross-lesson tests. Dependencies: T10, T14. Scope: M; `tutor/TutorContextBuilder.kt`, `tutor/TutorPromptPolicy.kt`, `tutor/SourceReferenceResolver.kt`, policy tests.

### T16 — Lesson tutor conversation

- [ ] Add accessible tutor UI with language/provider selection, suggested actions, streaming/non-stream states, cancel/retry, usage, and typed errors.
- [ ] Capture current lesson/timestamp/source/profile for requests; cancel/reject stale work on navigation/provider changes and avoid duplicate sends.
- [ ] Persist lesson-scoped history privately, provide deletion, safely render math/text/links, and make valid source references seek through the playback session.

Verification: UI/instrumentation tests for local and YouTube transcript lessons, cancellation, profile switching, RTL, font scaling, and reference seeking. Dependencies: T04, T09–T15. Scope: M; `tutor/TutorSession.kt`, `tutor/TutorHistoryStore.kt`, tutor UI, player integration, UI tests. Split rendering/history into separate slices if scope exceeds five files.

### T17 — Provider-independent YouTube tutor availability

- [ ] Offer transcript attachment when YouTube lesson content is unavailable; never claim embedded captions are accessible to the app.
- [ ] Run the same attached-transcript question through every supported adapter and retain source identity/timestamp behavior.
- [ ] Make general-help mode an explicit choice with a visible grounding label; no silent fallback from unavailable source to claimed lesson analysis.

Verification: Real YouTube lesson plus attached transcript; absent-source and provider-switch UI tests. Dependencies: G2, T16. Scope: M; lesson source UI, tutor availability logic, player integration, tests.

### T18 — Optional provider-native video capability

- [ ] Where T02/T13 prove support, implement native video input behind a capability flag and explicit data/cost disclosure; unsupported profiles continue using transcripts.
- [ ] Treat provider video timestamps as model-generated video references and validate format/bounds without mislabeling them as verified transcript excerpts.
- [ ] Handle inaccessible/private/unlisted/unsupported videos and provider limits; fail visibly or offer attachment without changing the user's chosen provider.

Verification: Live public-video input and human-checked references for every enabled profile/model; fixtures alone do not enable the capability. Dependencies: T17 and successful native-video probe. Scope: M; capability adapter, source policy, tutor UI, tests. If no selected provider supports this, mark the capability unavailable with evidence, not implemented/passed.

### G4 — Tutor checkpoint

- [ ] Local and YouTube attached-transcript tutoring works with the user's chosen supported API family.
- [ ] Grounding labels, source references, languages, unavailable-source behavior, errors, and cancellation pass.
- [ ] Native-video claims are limited to separately verified profiles; no forced provider fallback exists.

## M5 — Compatibility and release evidence

### T19 — Backup compatibility and privacy lifecycle

- [ ] Version backup metadata to restore YouTube course identities before dependent notes/progress; continue reading version 1 local backups.
- [ ] Preserve local media behavior, handle absent local courses explicitly, validate payload limits/IDs, and restore atomically without replacing parents.
- [ ] Verify credentials and private tutor history remain excluded; deletion and cache cleanup remove the intended data only.

Verification: Old/new backup round trips, malformed payload rollback, source-aware deletion, OS-backup rules, and secret scanning of exported fixtures. Dependencies: T03, G2, T09, T16. Scope: M; backup models/database service, repository, backup/privacy tests.

### T20 — Final technical and educator validation

- [ ] Run all build/lint/unit/instrumentation checks and the signed-device matrix; document compatibility by device, provider/model/profile, and tested capability.
- [ ] Run the plan's 48-question multilingual/source pilot with human review, plus native-video evaluation if enabled; repair failures and retain raw evidence without secrets.
- [ ] Record privacy/policy/credential review, recovery procedure, measured latency/usage, known limitations, and remaining evidence gaps; do not claim release-ready with required checks pending.

Verification: Evidence report includes command exit codes, device results, provider live results, pilot rubric/results/reviewer status, and regression outcomes. Dependencies: G4, T19. Scope: M per verification slice; evidence fixtures/reports and release notes. Split device, provider, and educational runs into separate tracked subtasks if needed.

### G5 — Completion checkpoint

- [ ] All required implementation acceptance criteria and automated checks pass.
- [ ] Signed-device playback, migrations, backup/privacy, and live provider compatibility are verified.
- [ ] Human educational review meets the proposed targets; no automated result is substituted for human approval.
- [ ] Excluded download functionality has not entered scope; local learning remains operational when online features fail.
- [ ] User-facing documentation accurately describes API compatibility, transcript prerequisites, provider-specific capabilities, and known limits.
