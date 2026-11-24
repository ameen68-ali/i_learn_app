# Execution plan: YouTube courses and a provider-independent lesson tutor

Date: 2026-11-24 (Asia/Baghdad)

Status: Implementation is present and automated verification is passing. Isolated physical-device validation is in progress. Live-provider and educator gates remain open; see [evidence](evidence/implementation.md) and [todo.md](todo.md).

## 1. Objective and scope

Deliver the previously discussed steps 1–3:

1. Explicit course/lesson sources and shared playback/progress interfaces.
2. YouTube playlist import, course organization, and online playback.
3. A lesson-grounded AI tutor using the user's chosen API provider, model, endpoint, and credentials.

Offline downloads, download-quality selection, stream extraction, and a download manager are excluded. Existing local-course playback remains supported. Gemini is an optional provider, not the required or preferred provider.

### Planning assumptions

- This remains a native Android application using Kotlin, Compose, Media3, and SQLite.
- Start with playlists readable without signing into a YouTube account. Private-playlist OAuth is a separate feature.
- Each installation manages its own API profiles. This does not introduce accounts, a hosted credential service, or multi-device synchronization.
- Use direct device-to-provider requests for the proposed BYOK implementation, with session-only credentials by default and encrypted persistence as an explicit option. This is a security tradeoff, not a claim that permanent credentials are safe from a compromised client. Validate provider requirements before public release; do not silently introduce a backend.
- Transcript grounding is the provider-independent baseline for both local and YouTube lessons. A provider's native video analysis is an optional capability.
- Prepare the complete plan now. Implement only in a subsequent implementation task; no application code is changed by this plan.

## 2. What the code currently establishes

Paths below are relative to `android/app/src/main/java/com/ilearn/app/`.

| Current implementation | Consequence for this work |
| --- | --- |
| `data/Models.kt`: required `Course.folderPath` and `Lesson.videoPath` | Add typed sources; never encode a YouTube watch page as a media-file path. |
| `data/CourseDatabaseHelper.kt`: database version 3 | Preserve existing IDs and records through additive migrations. Test both upgrading an existing database and creating a fresh one. |
| `insertCourseWithLessons` uses SQLite `CONFLICT_REPLACE` | Introduce transactional remote-course reconciliation; replacing parent/lesson rows can cascade-delete notes. |
| `data/CourseRepository.kt`: deletion recursively removes `folderPath` | Dispatch deletion by source and restrict local removal to an owned course directory. YouTube course deletion must not touch the filesystem. |
| `player/ILearnPlayerController.kt`: Media3 file/URI playback and timestamp seeking | Retain it for local media; add a separate YouTube player implementation. |
| `MainActivity.kt`: a concrete controller is wired directly into player navigation | Introduce a lesson-session boundary and bind callbacks to the current lesson ID. |
| `ui/screens/VideoPlayerScreen.kt`: direct native-player controls, notes, audio features | Render source-specific playback with common learning controls; local audio/dubbing tools do not apply to YouTube playback. |
| Backup version 1 stores progress and notes | Add source identity for restoring YouTube courses; preserve old-backup support and exclude credentials. |
| Manifest has `allowBackup=true` | Explicitly exclude credential material and private tutor state from OS backup/device transfer. |
| No test source tree or Gradle wrapper found; Gradle was not on PATH during planning | Establish reproducible build and test tooling before implementation. Java/adb commands exist, but their versions and usable SDK/device state remain unverified. |

No build, device playback test, live provider call, or educator evaluation was performed during planning.

## 3. Capability map and architecture

| Module | Responsibility | Dependencies |
| --- | --- | --- |
| `lesson-sources` | Persistent source identity, safe migration, source-aware deletion | Existing database/repository |
| `playback-session` | Active lesson, playback state, progress, lifecycle, timestamp seeking | `lesson-sources` |
| `youtube-courses` | Playlist metadata, import/refresh, embedded playback | `lesson-sources`, `playback-session` |
| `provider-profiles` | User-selected endpoint/model, capability configuration, credential handling | App settings and secure storage |
| `lesson-grounding` | Transcript ingestion, timestamped passages, provenance, retrieval | `lesson-sources` |
| `tutor` | Provider adapters, lesson-scoped requests, conversation UI, evidence references | `provider-profiles`, `lesson-grounding`, `playback-session` |

Keep these as packages inside the existing app initially. Separate Gradle modules, a vector database, and a server are unnecessary for the first release.

### Source identity

- Preserve every existing local course and lesson ID.
- Store course source type, remote playlist ID, and refresh state separately from local storage paths.
- Store lesson source type and remote video/playlist-item identifiers separately from local paths.
- Use a stable playlist-item identity within the course: a video may appear more than once in one playlist. A lesson's order is mutable metadata, never its identity.
- Reimporting the same playlist offers opening/refreshing the existing course instead of creating a duplicate.
- Playlist refresh is atomic and paginated. Network failure must not commit a partial lesson list or erase progress.
- New/reordered items update metadata. Removed/unavailable entries become explicit unavailable/removed entries; retain their user notes and progress until intentional deletion. Honor YouTube metadata refresh/deletion requirements without treating user-authored notes as fetched API metadata.

### Playback contract

Use one session with observable state: lesson ID, readiness, playing/paused state, position, duration, error, and supported controls. Commands include load, play, pause, seek, and release. Use the existing Media3 controller behind a local adapter and a YouTube IFrame/WebView adapter for online lessons.

The shared learning UI consumes this contract. Source-specific subtitle, quality, audio, and speed controls appear only when supported. Preserve YouTube controls/branding and avoid overlays that obscure the embedded player. Validate app identity/origin requirements on a signed Android build. Never expose tutor credentials to JavaScript or a WebView bridge.

Progress saving must carry an explicit lesson ID. Save on navigation/backgrounding as well as normal playback updates. Late events from the previous lesson must not overwrite the new lesson's state. Keep watched completion distinct from understanding; do not report watch progress as academic mastery.

## 4. User flows

### YouTube course

Library → Add course → YouTube playlist link → validate → preview title/creator/available items → Add → course lessons → embedded player.

Provide import progress, cancellation, actionable quota/network errors, manual refresh, unavailable-video states, and an external YouTube fallback. Course refresh must preserve notes/bookmarks/progress. Offline, retain useful local library information within policy requirements and explain that playback needs a connection.

### Provider configuration

Settings → AI providers → Add profile → choose API format → endpoint → model → API key → test connection → save profile → select active profile.

Support multiple named profiles, switching providers, manual model entry when model discovery is unavailable, and clear authentication/quota/model errors. No account password is requested. Do not treat a consumer chatbot subscription as an API credential.

### Grounded tutor

Open lesson → Tutor → see available lesson sources → select English/Arabic/bilingual → ask a question or choose Explain this moment / Give an example / Give me a hint / Ask me a question → receive an answer with source references → tap a reference to seek.

The request captures lesson ID, source revision, playback timestamp, language, provider profile, and conversation scope. Switching lesson/provider or closing the conversation cancels active work and prevents stale results appearing under another lesson. Store useful history in app-private storage, scoped per lesson; provide delete-history controls.

## 5. Provider independence: precise compatibility contract

There is no single protocol implemented by every AI service. Provider independence means a common internal tutor interface with replaceable API adapters, not assuming that every API accepts the same JSON or that every model understands a YouTube URL.

Initial supported API families:

| API family | Configuration | Expected baseline |
| --- | --- | --- |
| OpenAI-compatible Chat Completions | Custom HTTPS base URL, model, credentials | Text requests; streaming when the endpoint supports it |
| OpenAI Responses | Endpoint/profile and model | Separate request/response adapter; do not assume Chat Completions format |
| Anthropic Messages | Endpoint/profile, model, API version, credentials | Native message/system format and stream/error parsing |
| Google Gemini | Endpoint/profile, model, credentials | Native format; video input only when verified for that profile/model |

Other services that implement one of these formats can use a custom profile. Other protocols need an adapter implementing the same contract. Do not advertise untested services as certified compatible. No executable request templates or arbitrary scripts in provider settings. An arbitrary non-LLM API is outside this feature.

Represent capabilities explicitly: streaming, context/output limits, structured output, token usage reporting, and native video input. Capability claims must be tested or clearly user-configured. Unsupported capabilities produce a clear state rather than a broken request. Use configurable model identifiers; do not freeze today's model catalog into the architecture.

The tutor's retrieval, evidence references, prompts, history, and UI are shared across providers. Embeddings are not required: use local text retrieval and timestamp context first, so changing an API key does not force transcript reindexing or an extra paid embedding service.

## 6. Grounding and answer quality

### Provider-independent baseline

1. Read existing lesson VTT/SRT subtitles or let the user attach a transcript to the current local/YouTube lesson through Android's document picker.
2. Support VTT/SRT plus UTF-8 plain text. Plain text has source passages but no fabricated timestamps.
3. Parse into passages with source ID, lesson ID, language, optional time range, and content hash/revision.
4. Retrieve passages relevant to the question plus nearby context. For Explain this moment, prioritize passages around the current timestamp.
5. Send bounded relevant context and bounded conversation history to the selected provider. Do not send the whole course by default. Never silently truncate the only evidence needed to answer.
6. Require source IDs in answers. Validate IDs and available time bounds against the request's actual sources before making citations clickable.

Separate source identity validation from factual verification: a valid passage ID does not prove the answer correctly interprets it. Human evaluation remains necessary.

Transcript content is untrusted lesson data, not instructions. The tutor has no tools capable of accessing credentials, executing code, or changing course data. Render output safely; reject unsafe links and avoid automatic remote-image loading.

### YouTube availability

- Official embedded captions are not an app-readable transcript. The captions download API requires permission to edit the video.
- User-attached/creator-provided transcripts enable grounding with any supported text API.
- Optional native video analysis may be enabled for a verified provider/model. Passing a URL as ordinary text is not equivalent to video input.
- Label model-generated video timestamps as video references, not verified transcript excerpts. Validate numeric bounds where duration is known, then test their accuracy against the actual video.
- With no usable source, show “Lesson content is unavailable to the tutor” and offer transcript attachment. A user may explicitly choose general subject help, clearly separated from grounded lesson answers.
- Mathematical notation or diagrams absent from a transcript cannot be claimed as understood. Screenshot/PDF ingestion and general multimodal authoring are deferred.

## 7. Security, privacy, usage, and failure behavior

- Default to session-only AI keys; opt-in remembering encrypts secrets with AES-GCM using an Android Keystore key. Keys remain separate from course/chat SQLite records.
- Bind credentials to the selected profile and endpoint origin. Editing an endpoint requires confirmation of its new destination before sending a saved credential. Disable credential forwarding across redirects.
- Use HTTPS with normal certificate validation. Local-network model servers are an extension with a deliberately scoped network policy, not a reason to permit cleartext globally.
- No keys in logs, crash reports, URLs, prompts, analytics, exports, backups, WebView state, or screenshots of settings. Mask display; provide explicit replacement/deletion.
- Disclose exactly which provider receives questions, selected transcript passages, and history. Exclude personal notes by default; sending them requires a separate user action.
- Direct BYOK means the secret exists in application memory during requests. Encryption does not remove this limitation. Public-release credential architecture must be reviewed against each provider's current guidance; any required backend change is a separate recorded scope decision.
- Manage the YouTube project's metadata credential independently of AI profiles. Validate applicable restrictions, quotas, and signed-client behavior before release. Do not ship an unrestricted secret as a shortcut.
- Connection testing may incur a small API request; label it accordingly. Show returned usage where supported and label cost estimates as estimates. Provider billing is authoritative; an app limit is not a provider-enforced spending cap.
- Bound input, output, timeouts, history, and concurrency. Cancel promptly; cancellation may not eliminate provider charges. Do not automatically retry interrupted generation or queue paid requests for later reconnection.
- Treat rejected credentials, denied model access, rate limits, unsupported capabilities, oversized context, empty/malformed output, and interrupted streams as distinct recoverable states.

## 8. Execution sequence and gates

Detailed tasks are in [todo.md](todo.md). Each task is an implementation slice with tests; task IDs are stable.

| Milestone | Tasks | Deliverable | Exit gate |
| --- | --- | --- | --- |
| M0: Baseline and feasibility | T01–T02 | Reproducible test/build setup; signed-device embed probe; provider protocol evidence | G0: environment and external dependencies verified, unresolved constraints recorded |
| M1: Shared foundations | T03–T04 | Additive source migration and playback session; existing local course still works | G1: migration/data preservation and local regression tests pass |
| M2: YouTube courses | T05–T08 | Playlist preview/import/refresh, playback, resume, notes/bookmarks | G2: full signed-device YouTube flow and failure cases pass |
| M3: Provider choice | T09–T13 | Secure API profiles and four protocol adapters | G3: adapter contract tests; live results identified by actual provider/profile |
| M4: Lesson tutor | T14–T18 | Transcript grounding, tutor UI, references, YouTube-source handling | G4: grounded local/YouTube transcript flows work across supported protocols; optional video capability tested separately |
| M5: Compatibility and release | T19–T20 | Backup compatibility, privacy controls, evaluation, release evidence | G5: build/test/device/security and educator review requirements satisfied |

Gates are evidence checkpoints, not automatic requests for repeated user permission. Continue within authorized implementation scope once a gate passes. Stop a dependent slice when the required external capability cannot be demonstrated; record the limitation instead of silently weakening acceptance criteria.

No fixed completion date is promised before M0. Estimate effort per remaining task after toolchain, credential transport, and Android embedding probes establish the actual constraints.

## 9. Verification and definition of done

### Commands

From `android/`, after T01 creates and verifies the Gradle wrapper:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Before a wrapper exists, a verified compatible Gradle installation is required to bootstrap it. Current AGP is 8.5.0 and the README specifies Gradle 8.7/JDK 17; confirm that pairing before use. Do not claim these commands passed during planning. Signed release builds require the owner's existing signing configuration, never checked-in secrets. Check current target-SDK/distribution requirements before public submission; avoid unrelated upgrades.

### Automated coverage

- Unit: playlist URL parsing, remote identity/reconciliation, API serialization/stream/error contracts, transcript parsing/retrieval, reference resolution, source-aware deletion decisions, stale-request rejection.
- Database instrumentation: upgrade version 3 and older supported versions, create fresh database, transactional refresh rollback, no cascade loss, old/new backup restore.
- UI/instrumentation: import states, provider settings, credential deletion, navigation/progress, streaming/cancellation, source attachment and unavailable-source states.
- Transport fixtures: partial SSE chunks, unknown optional fields, no-stream endpoints, endpoint redirects, malformed data, 401/403/429/5xx, timeout and disconnect. Never record real keys in fixtures.

### Real runtime checks

- At least an API 24-compatible test environment and a current Android environment; include a signed physical-device WebView/YouTube check. No unsupported emulator result substitutes for it.
- Local course import, subtitles, playback speed, notes, bookmarks, resume, Arabic audio, and backup/restore remain usable after migration.
- Test a playlist spanning multiple API pages, repeated video entries, reorder/add/remove, embedding-disabled content, missing videos, quota failure, offline launch, process recreation, and rotation.
- Resume/seek target within two seconds once the player is ready in ad-free test segments; ad or unavailable states must not save invalid lesson progress. This is a proposed acceptance target to verify, not measured behavior.
- Run real-provider smoke checks for each release-supported adapter. If credentials/credits are unavailable, retain an explicit unverified status; fixtures do not prove provider compatibility.

### Educational evaluation

Use a 48-question pilot: two source modes (local transcript and YouTube lesson with attached transcript) × three language modes (English, Arabic, bilingual) × eight questions. Each group includes three conceptual/application questions including mathematics, two source-location questions, two questions absent from the source, and one adversarial instruction embedded in source material. Test provider-native video separately when supported.

Proposed release targets: all clickable references resolve to supplied sources; zero cross-lesson/credential leaks; absent-source cases are not falsely presented as sourced; at least 90% of answerable pilot responses are rated correct, relevant, and adequately grounded by a human reviewer. A critical mathematical error blocks release until repaired and rerun. Record rubric, examples, model/profile, and source revisions. Automated scoring does not count as educator approval. Larger efficacy claims need broader study beyond this pilot.

### Release evidence

Record build/lint/test exit codes, device/OS/WebView versions, tested provider/model/endpoint profiles, measured latency and usage, migration results, evaluation results, known limitations, and reviewer status. Public release requires privacy text, YouTube attribution/policy checks, and credentials review. Keep optional native-video functionality disabled if it has not passed its separate checks.

## 10. Rollout and recovery

- Ship/review foundations, YouTube courses, and tutor as separate milestones. Keep feature availability explicit; failures of AI or YouTube must not disable local study.
- Use transactional, additive migrations and atomic remote refreshes. No database resets, table replacement, or course-media recopy as a migration strategy.
- Validate upgrade on a disposable database copy. Keep user-controlled backup/recovery instructions before testing a production database.
- Do not assume an older binary can open a newer schema. Prefer forward fixes; any rollback requires a tested compatible build or restoring a protected backup. Do not downgrade by deleting data.
- Before every implementation slice, inspect the working tree and preserve unrelated changes. No commit, push, deployment, or real-key use is part of this planning task.

## 11. Deferred scope

Offline media downloads and quality selection; YouTube stream/caption scraping; private-playlist OAuth; cloud accounts/synchronization; voice tutor; screenshot/PDF ingestion; automatic speech recognition; automatic question banks/spaced repetition; course-wide semantic/vector infrastructure; arbitrary executable provider templates. These require separate scope decisions.

## 12. External prerequisites and unresolved decisions

The user does not need to provide secrets to complete this plan. During implementation, configure test credentials locally through an excluded mechanism or the app itself.

- Verify JDK/SDK, Gradle bootstrap, and available emulator/physical device (T01).
- Establish YouTube project/quota configuration and validate the selected signed-client credential restrictions (T02/T05).
- Confirm direct BYOK's acceptable distribution/security model against provider guidance before public release (T02/T09). No shared developer AI key is proposed.
- Obtain live test access to each provider family claimed at release; unsupported/unavailable profiles remain visibly unverified.
- Select representative course sources and a human reviewer for the teaching-quality pilot (T20).

## 13. Official references

Checked during planning on 2026-11-24; recheck relevant pages when implementing an adapter or preparing release.

- [YouTube playlist items](https://developers.google.com/youtube/v3/docs/playlistItems/list): paginated playlist metadata.
- [YouTube IFrame API](https://developers.google.com/youtube/iframe_api_reference): playback, events, Android WebView identity considerations.
- [YouTube developer policies](https://developers.google.com/youtube/terms/developer-policies) and [required functionality](https://developers.google.com/youtube/terms/required-minimum-functionality): playback and API-data handling obligations.
- [YouTube caption download](https://developers.google.com/youtube/v3/docs/captions/download): edit permission is required; public captions are not a universal transcript API.
- [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat) and [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create): distinct protocol adapters.
- [Anthropic Messages](https://platform.claude.com/docs/en/api/messages): native request/response protocol.
- [Gemini video input](https://ai.google.dev/gemini-api/docs/video-understanding): optional provider-specific public-video input, not a common capability of all APIs.
- [Gemini key guidance](https://ai.google.dev/gemini-api/docs/api-key) and [OpenAI key safety](https://help.openai.com/en/articles/5112595-best-practices-for-api-key-safety): client credential limitations.
- [Android Keystore](https://developer.android.google.cn/privacy-and-security/keystore?hl=en): protect encryption keys for local credential storage.
